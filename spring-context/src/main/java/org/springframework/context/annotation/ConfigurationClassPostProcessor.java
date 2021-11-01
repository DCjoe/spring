/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.PassThroughSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.ApplicationStartupAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ConfigurationClassEnhancer.EnhancedConfiguration;
import org.springframework.core.NativeDetector;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import java.util.*;

/**
 * {@link BeanFactoryPostProcessor} used for bootstrapping processing of
 * {@link Configuration @Configuration} classes.
 *
 * <p>Registered by default when using {@code <context:annotation-config/>} or
 * {@code <context:component-scan/>}. Otherwise, may be declared manually as
 * with any other {@link BeanFactoryPostProcessor}.
 *
 * <p>This post processor is priority-ordered as it is important that any
 * {@link Bean @Bean} methods declared in {@code @Configuration} classes have
 * their corresponding bean definitions registered before any other
 * {@code BeanFactoryPostProcessor} executes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 3.0
 */
public class ConfigurationClassPostProcessor implements BeanDefinitionRegistryPostProcessor,
		PriorityOrdered, ResourceLoaderAware, ApplicationStartupAware, BeanClassLoaderAware, EnvironmentAware {

	/**
	 * A {@code BeanNameGenerator} using fully qualified class names as default bean names.
	 * <p>This default for configuration-level import purposes may be overridden through
	 * {@link #setBeanNameGenerator}. Note that the default for component scanning purposes
	 * is a plain {@link AnnotationBeanNameGenerator#INSTANCE}, unless overridden through
	 * {@link #setBeanNameGenerator} with a unified user-level bean name generator.
	 * @since 5.2
	 * @see #setBeanNameGenerator
	 */
	public static final AnnotationBeanNameGenerator IMPORT_BEAN_NAME_GENERATOR =
			FullyQualifiedAnnotationBeanNameGenerator.INSTANCE;

	private static final String IMPORT_REGISTRY_BEAN_NAME =
			ConfigurationClassPostProcessor.class.getName() + ".importRegistry";


	private final Log logger = LogFactory.getLog(getClass());

	private SourceExtractor sourceExtractor = new PassThroughSourceExtractor();

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	@Nullable
	private Environment environment;

	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	private MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();

	private boolean setMetadataReaderFactoryCalled = false;

	private final Set<Integer> registriesPostProcessed = new HashSet<>();

	private final Set<Integer> factoriesPostProcessed = new HashSet<>();

	@Nullable
	private ConfigurationClassBeanDefinitionReader reader;

	private boolean localBeanNameGeneratorSet = false;

	/* Using short class names as default bean names by default. */
	private BeanNameGenerator componentScanBeanNameGenerator = AnnotationBeanNameGenerator.INSTANCE;

	/* Using fully qualified class names as default bean names by default. */
	private BeanNameGenerator importBeanNameGenerator = IMPORT_BEAN_NAME_GENERATOR;

	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;


	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;  // within PriorityOrdered
	}

	/**
	 * Set the {@link SourceExtractor} to use for generated bean definitions
	 * that correspond to {@link Bean} factory methods.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new PassThroughSourceExtractor());
	}

	/**
	 * Set the {@link ProblemReporter} to use.
	 * <p>Used to register any problems detected with {@link Configuration} or {@link Bean}
	 * declarations. For instance, an @Bean method marked as {@code final} is illegal
	 * and would be reported as a problem. Defaults to {@link FailFastProblemReporter}.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Set the {@link MetadataReaderFactory} to use.
	 * <p>Default is a {@link CachingMetadataReaderFactory} for the specified
	 * {@linkplain #setBeanClassLoader bean class loader}.
	 */
	public void setMetadataReaderFactory(MetadataReaderFactory metadataReaderFactory) {
		Assert.notNull(metadataReaderFactory, "MetadataReaderFactory must not be null");
		this.metadataReaderFactory = metadataReaderFactory;
		this.setMetadataReaderFactoryCalled = true;
	}

	/**
	 * Set the {@link BeanNameGenerator} to be used when triggering component scanning
	 * from {@link Configuration} classes and when registering {@link Import}'ed
	 * configuration classes. The default is a standard {@link AnnotationBeanNameGenerator}
	 * for scanned components (compatible with the default in {@link ClassPathBeanDefinitionScanner})
	 * and a variant thereof for imported configuration classes (using unique fully-qualified
	 * class names instead of standard component overriding).
	 * <p>Note that this strategy does <em>not</em> apply to {@link Bean} methods.
	 * <p>This setter is typically only appropriate when configuring the post-processor as a
	 * standalone bean definition in XML, e.g. not using the dedicated {@code AnnotationConfig*}
	 * application contexts or the {@code <context:annotation-config>} element. Any bean name
	 * generator specified against the application context will take precedence over any set here.
	 * @since 3.1.1
	 * @see AnnotationConfigApplicationContext#setBeanNameGenerator(BeanNameGenerator)
	 * @see AnnotationConfigUtils#CONFIGURATION_BEAN_NAME_GENERATOR
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		Assert.notNull(beanNameGenerator, "BeanNameGenerator must not be null");
		this.localBeanNameGeneratorSet = true;
		this.componentScanBeanNameGenerator = beanNameGenerator;
		this.importBeanNameGenerator = beanNameGenerator;
	}

	@Override
	public void setEnvironment(Environment environment) {
		Assert.notNull(environment, "Environment must not be null");
		this.environment = environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		Assert.notNull(resourceLoader, "ResourceLoader must not be null");
		this.resourceLoader = resourceLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(resourceLoader);
		}
	}

	@Override
	public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(beanClassLoader);
		}
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		this.applicationStartup = applicationStartup;
	}

	/**
	 * Derive further bean definitions from the configuration classes in the registry.
	 */
	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		//这里生成一个注册ID registryId，为了防止同一个注册器多次调用,SET集合本身具有去重功能，所以用SET存放
		int registryId = System.identityHashCode(registry);
		if (this.registriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanDefinitionRegistry already called on this post-processor against " + registry);
		}
		if (this.factoriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + registry);
		}
		this.registriesPostProcessed.add(registryId);

		/**
		 * 这个方法是配置类处理的核心所在，它做的时期：
		 * 1.处理@Component注解；
		 * 2.处理@ComponentScan注解；
		 * 3.处理@PropertySource
		 * 4.处理@Import注解；
		 * 5.处理@ImportResouce注解
		 *
		 * 简单来说就是根据我们的配置类找到所有符合条件的class对象，然后生成BeanDefinition，放入beanDefinitionMap中
		 */
		processConfigBeanDefinitions(registry);
	}

	/**
	 * Prepare the Configuration classes for servicing bean requests at runtime
	 * by replacing them with CGLIB-enhanced subclasses.
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		int factoryId = System.identityHashCode(beanFactory);
		if (this.factoriesPostProcessed.contains(factoryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + beanFactory);
		}
		this.factoriesPostProcessed.add(factoryId);
		if (!this.registriesPostProcessed.contains(factoryId)) {
			// BeanDefinitionRegistryPostProcessor hook apparently not supported...
			// Simply call processConfigurationClasses lazily at this point then.
			processConfigBeanDefinitions((BeanDefinitionRegistry) beanFactory);
		}

		enhanceConfigurationClasses(beanFactory);
		beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory));
	}

	/**
	 *
	 * 这个方法特别重要，首先：
	 * 1.spring先将我们加了注册的配置类从bd map中拿出来获取bd，放入configCandidates中
	 * 2.然后循环解析配置类，从配置类中获取@ComponentScan中配置的扫描路径
	 * 3.根据路径spring通过asm技术扫描我们的类，扫描成功的放入bd map中
	 * 4.处理@import注解的相关实现
	 * 4.*****（这个概念非常重要，一定要理解）这个方法还有个非常重要的概念就是，我们的配置类上是否有加@Configuration(这个注解的作用不言而喻，可以说非常重要，
	 *简单明了就是如果加了@Configuration，那么它就是一个全注解FULL，没有加就是一个普通的组件LITE
	 * 而区别就是加了@Configuration注解的，spring会帮我们生成一个代理类，是一个CGLIB的代理类，没有加@Configuration，
	 * 就不会生成一个CGLIB的代理类，那么有什么区别？？
	 * 区别：如果是由@Configuration，那么通常我们的Appconfig配置类中会有一些方法，将普通的类返回为一个Bean，也就是加了一个@Bean
	 * 如果我们在配置类中调用本配置类的@Bean方法，就是一个本类的普通方法调用，如果加了@Configuration，那么spring会冲容器中给我们
	 * 拿出这个对象出来，也就是说这个对象的产生只有一次，说白了，加了@Configuration,那么spring就会为我们这个配置类创建一个factoryBean
	 * 我们的拿的对象都是从工厂bean中取的，这个工厂bean是由cglib代理的。加不加@configuraation的最重要区别就是加了注解我们拿对象从工厂中拿
	 * 而不加，拿几次就创建几次
	 *
	 *
	 *
	 *
	 * *****这个方法特别复杂，太多循环，夹杂着递归，有时候看的脑壳涨
	 * 1.首先在容器中获取所有的BeanDefinition名字集合；
	 * 2.循环这个集合，找出符合条件的也就是属于配置类的BeanDefinition名字；
	 * 3.然后调用parse方法进行解析，解析的时候通过循环进行处理，一个配置类一个配置类的进行处理
	 * 4.每一个配置类的工作流程是：
	 *   a.判断是否加了@Componet注解，如果加了，处理@Component所在类的内部类，因为内部类可能有其他注解，然后这里是一个递归处理；
	 *   b.判断是否有@ProperResource注解，如果有，处理@ProperResource,将配置的资源文件解析出来放入到Environment中；
	 *   c.处理@ComponScan注解，执行真正的扫描工作，将符合条件的创建ConfigurationClass;
	 *   d.处理@Import注解，@Import注解有三种类型，普通类的导入，ImportSelector，ImportBeanDefinitionRegistrar；
	 *   e.处理@ImportResource注解，@ImportResource注解一般导入的是spring的配置文件，配置的是<Bean />;
	 *   f.处理每个配置类的@Bean方法
	 *   在上面的c、d中都可能存在递归的处理，首先c中扫描出来的BeanDefinition然后递归处理@Coponent所在类，看是否有a b c d e f的情况产生
	 *   简单来说就是递归给你把所有的配置类都给解析的干干净净；
	 *   d中主要是@Import的注解的递归，因为有三种类型，只有ImportBeanDefinitionRegistrar类型的是加入集合中循环处理，其他的两种类型
	 *   都是递归处理，首先普通类，是递归处理，生成一个ConfigurationClass,然后设置一个属性ImportBy，ImportBy就是谁导入它的
	 *   谁就是ImportBy的集合一员，最后根据是否有ImportBy来产生一个最基本的BeanDefinition，而ImportSelector的回调方法是会返回
	 *   一个待处理的类集合数组，数组里面的就是要导入的类列表，也会产生@Import的三种类型，反正就是有时递归
	 *   看这个方法的时候一定要注意结合上下文来分析，反正注释倒是写的挺全，但是看注释还不如直接上代码，注释只是根据个人自己的理解去写
	 *   但是代码实现情况，每个人都会不同的理解和注释，只要理解即可.
	 *  5.配置类解析完成过后，然后do while循环中又重新重容器中拿到BeanDefinition中的数量，然后和之前获取的数量进行比较，最后找出新产生的
	 *  BeanDefinition，然后判断是否是一个符合条件的配置类，如果是一个配置类，然后又添加到配置类候选者集合中 ，一直循环，直到将所有的配置类处理完成
	 *
	 * Build and validate a configuration model based on the registry of
	 *
	 * {@link Configuration} classes.
	 */
	public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) {
		//声明一个list，用来存放我们的配置类（什么配置类？？），比如我们的配置类是AppConfig，
		//这个类我们是通过spring提供的方法register注册到DefaultListableBeanFactory的bd map中的
		//所以这里的configCandidates主要为了先存放我们的配置类，因为配置类可以有多个，所以这里是一个集合
		List<BeanDefinitionHolder> configCandidates = new ArrayList<>();
		//这里从registry中拿到所有已经注册到DefaultListableBeanFactory中的bd map集合
		// （DefaultListableBeanFactory继承了BeanDefinitionRegistry）
		//这里是拿到容器中所有的BeanDefinition的名字，如果你只设置了一个配置类，你没有手动添加一些bean工厂的后置处理器的话，那么这里
		//拿到的就只有6个，有5个bd是spring的内置的bd，有一个bd是你的配置类，而这里我们主要就是拿到这个配置bd，然后进行处理
		String[] candidateNames = registry.getBeanDefinitionNames();

		for (String beanName : candidateNames) {
			BeanDefinition beanDef = registry.getBeanDefinition(beanName);
			//这里判断我们的BeanDefinition中是否有一个configurationClass属性，configurationClass是配置类信息封装对象
			if (beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE) != null) {
				if (logger.isDebugEnabled()) {
					logger.debug("Bean definition has already been processed as a configuration class: " + beanDef);
				}
			}
			/**
			 * 这里的判断就是来判断我当前循环的这个BeanDefinition是否是一个配置类的候选者,并且下面的这个逻辑判断了如果你加了
			 * @Configuration注解，如果有这个注解，那么在bd中设置一个属性full，表示全注解的意思，而如果没有加@Configuration注解
			 * 那么认为是一个部分注解的配置类，它们二者有什么区别呢？区别就是如果是全注解，那么AppConfig这个类会生成一个代理对象
			 * 而如果不是全注解则不生成代理对象，也就是说如果加了@Configuration，那么如果你getBean的话，返回的是一个代理对象
			 */
			//这里就是配置类判断的核心代码了，包括是否全注解的添加也在这里，checkConfigurationClassCandidate方法最重要的两点：
			//判断当前的bd中的类是否实现了@Configuration，如果是则设置一个属性FUll,否则设置属性LITE
			//这个方法返回一个true or false，如果是true，则我们当前循环的这个bd是一个配置类的候选者，加入configCandidates
			else if (ConfigurationClassUtils.checkConfigurationClassCandidate(beanDef, this.metadataReaderFactory)) {
				configCandidates.add(new BeanDefinitionHolder(beanDef, beanName));
			}
		}

		// Return immediately if no @Configuration classes were found
		if (configCandidates.isEmpty()) {
			return;
		}

		// Sort by previously determined @Order value, if applicable
		/**
		 * 对我们扫描到的配置bd进行排序,看谁先执行，就是说我们如果希望你设置的比如3个配置类，那个先执行，那个后执行， 也就是说
		 * 你可以控制它的执行顺序，如果这样的话，你可以实现了一个Order接口，实现了Order接口，根据Order的大小进行排序，最后根据Order最小
		 * 的最先执行，其他的依次执行，如果你都没有加Order，那么这个Order的值默认是Integer.Max_VALUE
		 */
		configCandidates.sort((bd1, bd2) -> {
			int i1 = ConfigurationClassUtils.getOrder(bd1.getBeanDefinition());
			int i2 = ConfigurationClassUtils.getOrder(bd2.getBeanDefinition());
			return Integer.compare(i1, i2);
		});

		// Detect any custom bean name generation strategy supplied through the enclosing application context
		//我们的注册器BeanDefinitionRegistry肯定是SingletonBeanRegistry，看工厂类DefaultListableBeanFactory就知道
		//这里就是判断你应用是否实现了beanName的生成器，如果没有就使用默认的生成器
		SingletonBeanRegistry sbr = null;
		if (registry instanceof SingletonBeanRegistry) {
			sbr = (SingletonBeanRegistry) registry;
			if (!this.localBeanNameGeneratorSet) {
				BeanNameGenerator generator = (BeanNameGenerator) sbr.getSingleton(
						AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR);
				if (generator != null) {
					this.componentScanBeanNameGenerator = generator;
					this.importBeanNameGenerator = generator;
				}
			}
		}

		//因为后置的扫描要处理@ProperResouce注解，所以如果这里之前没有设置environment，那么这里重新创建
		//其实在spring启动准备的时候就已经加了这个环境的bean了，所以这里肯定是不能为空，只是spring设计的严谨一点
		if (this.environment == null) {
			this.environment = new StandardEnvironment();
		}

		// Parse each @Configuration class
		//申明一个配置类解析器，用来解析我们的配置类
		ConfigurationClassParser parser = new ConfigurationClassParser(
				this.metadataReaderFactory, this.problemReporter, this.environment,
				this.resourceLoader, this.componentScanBeanNameGenerator, registry);

		//这里重新申明了一个SET结合来存放我们上面得到的配置类，为什么要用一次set来存放？（set去重，set底层实现就是map的key，所以是可以去重的）
		Set<BeanDefinitionHolder> candidates = new LinkedHashSet<>(configCandidates);
		//存放容器中已经被处理过的配置类集合
		Set<ConfigurationClass> alreadyParsed = new HashSet<>(configCandidates.size());
		/**
		 * 下面是开启了一个do while 循环，每次拿到的配置类集合进行处理，因为每次处理完成过后还可能出现新的配置类，所以这里每次
		 * 获取配置类列表，然后处理，处理完成过后又重新获取新的配置类列表，直到将所有的配置类处理完成
		 *
		 */
		do {
			StartupStep processConfig = this.applicationStartup.start("spring.context.config-classes.parse");
			//下面这个方法非常重要，我起初刚研究spring源码的时候，为了找到这个核心代码已经放弃过很多次，因为我在前面的代码就断片了，debug也跟不到这里
			//因为实在太多类了，跟着跟着就丢了；所以下面的解析类就是来解析我们配置类配置了扫码包的路径
			//和一系列的其他操作，比如@import的处理等，逻辑非常复杂，核心之核心之处就在这里
			parser.parse(candidates);
			parser.validate();

			/**
			 * 将扫描到的配置类都放入了parser.getConfigurationClasses()，所以这里获取扫描得到的配置类列表；
			 * 这里说明一下，在spring的扫描架构中，只要是扫描到的@Component它都认为一个配置类
			 * 1.@Compoent为一个配置类，表示被扫描到的；
			 * 2.@Import为一个配置类，其中包含了属性ImportBy，表示被谁导入的；（扩展点）
			 * 3.beanMethods表示配置类中的@Bean方法列表；
			 * 4.importedResources属性表示配置类要导入的资源列表；
			 * 5.importBeanDefinitionRegistrars属性表示被@Import导入的Bean定义注册器（扩展点）
			 */
			Set<ConfigurationClass> configClasses = new LinkedHashSet<>(parser.getConfigurationClasses());
			configClasses.removeAll(alreadyParsed);

			// Read the model and create bean definitions based on its content
			if (this.reader == null) {
				this.reader = new ConfigurationClassBeanDefinitionReader(
						registry, this.sourceExtractor, this.resourceLoader, this.environment,
						this.importBeanNameGenerator, parser.getImportRegistry());
			}
			//这里非常重要，就是我们在扩展spring的时候定义了很多的BeanFactoryProcessor和@import的注解类
			//在上面的parse中已经把@compenet的类扫描成了bd，把自定义的后置器和@import的类扫描出来了放入了configClasses中
			//下面的这个方法就是为了处理添加到了configClasses中的类，将其注册为bd
			this.reader.loadBeanDefinitions(configClasses);
			//将已经处理过的配置类放入已处理的列表中
			alreadyParsed.addAll(configClasses);
			processConfig.tag("classCount", () -> String.valueOf(configClasses.size())).end();
			//将当前已经处理过的配置类集合清除（它是do while循环退出的依据）
			candidates.clear();
			/**
			 * 如果说从容器中拿到的的BeanDefinition的数量是大于一开始获取的BeanDefinition数量candidateNames.length
			 * 那么就证明在上面的解析配置类的过程中出现了新的配置类（肯定会出现的，配置类只要满足@Component就会成为一个配置类）
			 * 所以它又还会去处理，但是在parse方法里面已经做了去重，@Component中如果有@Bean这些也会进行处理
			 * 所以这里再次去处理也是没有问题的；下面代码的逻辑：
			 * 重新获取系统中所有的BeanDefinition，然后筛选掉上面已经处理过的BeanDefinition，然后去检查是否
			 * 是配置类的候选者，也就是判断是否是一个配置类，如果是一个配置类，那么添加到配置类处理集合candidates，
			 * 再次去循环，直到系统中的所有配置类都已经被处理过
			 */
			if (registry.getBeanDefinitionCount() > candidateNames.length) {
				//从容器中重新拿出来的所有的Bean的名字集合
				String[] newCandidateNames = registry.getBeanDefinitionNames();
				//上一次获取的BeanDefinition名字集合
				Set<String> oldCandidateNames = new HashSet<>(Arrays.asList(candidateNames));
				//从之前到现在已经解析过的配置类名字集合
				Set<String> alreadyParsedClasses = new HashSet<>();
				for (ConfigurationClass configurationClass : alreadyParsed) {
					alreadyParsedClasses.add(configurationClass.getMetadata().getClassName());
				}
				//循环新获取的配置类集合名字
				for (String candidateName : newCandidateNames) {
					//去掉已经处理过的
					if (!oldCandidateNames.contains(candidateName)) {
						//如果符合配置类的候选者判断，然后加入到处理集合列表中，开始新一轮的循环
						BeanDefinition bd = registry.getBeanDefinition(candidateName);
						if (ConfigurationClassUtils.checkConfigurationClassCandidate(bd, this.metadataReaderFactory) &&
								!alreadyParsedClasses.contains(bd.getBeanClassName())) {
							candidates.add(new BeanDefinitionHolder(bd, candidateName));
						}
					}
				}
				candidateNames = newCandidateNames;
			}
		}
		while (!candidates.isEmpty());

		// Register the ImportRegistry as a bean in order to support ImportAware @Configuration classes
		if (sbr != null && !sbr.containsSingleton(IMPORT_REGISTRY_BEAN_NAME)) {
			sbr.registerSingleton(IMPORT_REGISTRY_BEAN_NAME, parser.getImportRegistry());
		}

		if (this.metadataReaderFactory instanceof CachingMetadataReaderFactory) {
			// Clear cache in externally provided MetadataReaderFactory; this is a no-op
			// for a shared cache since it'll be cleared by the ApplicationContext.
			((CachingMetadataReaderFactory) this.metadataReaderFactory).clearCache();
		}
	}

	/**
	 * Post-processes a BeanFactory in search of Configuration class BeanDefinitions;
	 * any candidates are then enhanced by a {@link ConfigurationClassEnhancer}.
	 * Candidate status is determined by BeanDefinition attribute metadata.
	 * @see ConfigurationClassEnhancer
	 */
	public void enhanceConfigurationClasses(ConfigurableListableBeanFactory beanFactory) {
		StartupStep enhanceConfigClasses = this.applicationStartup.start("spring.context.config-classes.enhance");
		Map<String, AbstractBeanDefinition> configBeanDefs = new LinkedHashMap<>();
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			Object configClassAttr = beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE);
			AnnotationMetadata annotationMetadata = null;
			MethodMetadata methodMetadata = null;
			if (beanDef instanceof AnnotatedBeanDefinition) {
				AnnotatedBeanDefinition annotatedBeanDefinition = (AnnotatedBeanDefinition) beanDef;
				annotationMetadata = annotatedBeanDefinition.getMetadata();
				methodMetadata = annotatedBeanDefinition.getFactoryMethodMetadata();
			}
			if ((configClassAttr != null || methodMetadata != null) && beanDef instanceof AbstractBeanDefinition) {
				// Configuration class (full or lite) or a configuration-derived @Bean method
				// -> eagerly resolve bean class at this point, unless it's a 'lite' configuration
				// or component class without @Bean methods.
				AbstractBeanDefinition abd = (AbstractBeanDefinition) beanDef;
				if (!abd.hasBeanClass()) {
					boolean liteConfigurationCandidateWithoutBeanMethods =
							(ConfigurationClassUtils.CONFIGURATION_CLASS_LITE.equals(configClassAttr) &&
								annotationMetadata != null && !ConfigurationClassUtils.hasBeanMethods(annotationMetadata));
					if (!liteConfigurationCandidateWithoutBeanMethods) {
						try {
							abd.resolveBeanClass(this.beanClassLoader);
						}
						catch (Throwable ex) {
							throw new IllegalStateException(
									"Cannot load configuration class: " + beanDef.getBeanClassName(), ex);
						}
					}
				}
			}
			if (ConfigurationClassUtils.CONFIGURATION_CLASS_FULL.equals(configClassAttr)) {
				if (!(beanDef instanceof AbstractBeanDefinition)) {
					throw new BeanDefinitionStoreException("Cannot enhance @Configuration bean definition '" +
							beanName + "' since it is not stored in an AbstractBeanDefinition subclass");
				}
				else if (logger.isInfoEnabled() && beanFactory.containsSingleton(beanName)) {
					logger.info("Cannot enhance @Configuration bean definition '" + beanName +
							"' since its singleton instance has been created too early. The typical cause " +
							"is a non-static @Bean method with a BeanDefinitionRegistryPostProcessor " +
							"return type: Consider declaring such methods as 'static'.");
				}
				configBeanDefs.put(beanName, (AbstractBeanDefinition) beanDef);
			}
		}
		if (configBeanDefs.isEmpty() || NativeDetector.inNativeImage()) {
			// nothing to enhance -> return immediately
			enhanceConfigClasses.end();
			return;
		}

		ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer();
		for (Map.Entry<String, AbstractBeanDefinition> entry : configBeanDefs.entrySet()) {
			AbstractBeanDefinition beanDef = entry.getValue();
			// If a @Configuration class gets proxied, always proxy the target class
			beanDef.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
			// Set enhanced subclass of the user-specified bean class
			Class<?> configClass = beanDef.getBeanClass();
			Class<?> enhancedClass = enhancer.enhance(configClass, this.beanClassLoader);
			if (configClass != enhancedClass) {
				if (logger.isTraceEnabled()) {
					logger.trace(String.format("Replacing bean definition '%s' existing class '%s' with " +
							"enhanced class '%s'", entry.getKey(), configClass.getName(), enhancedClass.getName()));
				}
				beanDef.setBeanClass(enhancedClass);
			}
		}
		enhanceConfigClasses.tag("classCount", () -> String.valueOf(configBeanDefs.keySet().size())).end();
	}


	private static class ImportAwareBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

		private final BeanFactory beanFactory;

		public ImportAwareBeanPostProcessor(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public PropertyValues postProcessProperties(@Nullable PropertyValues pvs, Object bean, String beanName) {
			// Inject the BeanFactory before AutowiredAnnotationBeanPostProcessor's
			// postProcessProperties method attempts to autowire other configuration beans.
			if (bean instanceof EnhancedConfiguration) {
				((EnhancedConfiguration) bean).setBeanFactory(this.beanFactory);
			}
			return pvs;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			if (bean instanceof ImportAware) {
				ImportRegistry ir = this.beanFactory.getBean(IMPORT_REGISTRY_BEAN_NAME, ImportRegistry.class);
				AnnotationMetadata importingClass = ir.getImportingClassFor(ClassUtils.getUserClass(bean).getName());
				if (importingClass != null) {
					((ImportAware) bean).setImportMetadata(importingClass);
				}
			}
			return bean;
		}
	}

}
