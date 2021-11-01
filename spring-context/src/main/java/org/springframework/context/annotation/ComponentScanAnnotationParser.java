/*
 * Copyright 2002-2018 the original author or authors.
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

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.*;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Parser for the @{@link ComponentScan} annotation.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @see ClassPathBeanDefinitionScanner#scan(String...)
 * @see ComponentScanBeanDefinitionParser
 */
class ComponentScanAnnotationParser {

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	private final BeanNameGenerator beanNameGenerator;

	private final BeanDefinitionRegistry registry;


	public ComponentScanAnnotationParser(Environment environment, ResourceLoader resourceLoader,
			BeanNameGenerator beanNameGenerator, BeanDefinitionRegistry registry) {

		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.beanNameGenerator = beanNameGenerator;
		this.registry = registry;
	}


	/**
	 * 这个方法最后调用了一个doScan方法真正完成了注解的扫描，
	 * 将符合条件的类扫描成AnnotatedBeanDefinition，然后注册到bdmap中
	 * 在进行扫描之前还需进行一些注解上的判断和参数准备
	 * @param componentScan @ComponentScan
	 * @param declaringClass
	 * @return
	 */
	public Set<BeanDefinitionHolder> parse(AnnotationAttributes componentScan, final String declaringClass) {
		//看到这里是不是觉得这个类很眼熟？？？这个类在我们初始化注解应用上下文的时候，也就是创建容器的时候也创了一个ClassPathBeanDefinitionScanner对象
		//但是这边这个对象和创建容器时候的扫描对象不是同一个，这边是新创建的，那为什么要创建两个呢？
		//原因：因为spring的注解配置应用程序上下文 也就是我们程序入口AnnotationConfigApplicationContext这个类
		//实现的接口AnnotationConfigRegistry，这个里面提供了两个接口方法，一个是register，也就是注册我们的配置类的
		//而另一个是scan，因为AnnotationConfigApplicationContext是我们实例化创建容器的，所以可以通过
		//AnnotationConfigApplicationContext产生的对象直接调用scan来扫描，也就是说spring容器提供了一个供
		//用户自己去扫描自定义的特殊的类路径，而不需要spring容器去推断或者经过一系列的负责操作来得到你的扫描包路径
		//而我们这边的这个ClassPathBeanDefinitionScanner扫描对象就是spring通过一系列的推断或者操作得到我们的扫描
		//包路径，然后自己又创了一个新的扫描对象来处理我们的扫描操作
		//这里在构建ClassPathBeanDefinitionScanner对象的时候是添加了一个默认的过滤器，包含过滤器，就是包含了我们的
		//@Component注解，后面验证要用
		ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(this.registry,
				componentScan.getBoolean("useDefaultFilters"), this.environment, this.resourceLoader);

		//这里就是看你的@CompoentScan是否加了自定义的Bean生成器，如果你加了Bean的生成器，那么就会使用你的Bean生成器，否则就用默认的生成器
		Class<? extends BeanNameGenerator> generatorClass = componentScan.getClass("nameGenerator");
		boolean useInheritedGenerator = (BeanNameGenerator.class == generatorClass);
		scanner.setBeanNameGenerator(useInheritedGenerator ? this.beanNameGenerator :
				BeanUtils.instantiateClass(generatorClass));

		//这个在mvc中用
		ScopedProxyMode scopedProxyMode = componentScan.getEnum("scopedProxy");
		if (scopedProxyMode != ScopedProxyMode.DEFAULT) {
			scanner.setScopedProxyMode(scopedProxyMode);
		}
		else {
			Class<? extends ScopeMetadataResolver> resolverClass = componentScan.getClass("scopeResolver");
			scanner.setScopeMetadataResolver(BeanUtils.instantiateClass(resolverClass));
		}

		/**
		 * 在scanner中，扫描器对象用下面的几个操作是添加了全局的公共的一些参数，比如过滤器，延迟加载的一些属性
		 * 比如说你在@ComponScan中添加了一个属性lazyInit为true，表示全局启用延迟加载，那么只要你在某个bean中没有指定是否
		 * 延迟加载，那么默认就是延迟加载的，这里只是设置了默认的一些参数，下面在创建BeanDefinition的时候会应用到每个BeanDefinition
		 */
		//设置资源路径扫描的Pattern，默认是"**/*.class
		scanner.setResourcePattern(componentScan.getString("resourcePattern"));

		//添加包含的过滤器，我们在配置@Component可以添加过滤器
		//也就是说如果你添加了一些过滤器，如果添加了过滤器，那么所有的配置类都要经过它的包含过滤器，如果不匹配包含的过滤器
		//那么spring就不会认为它是一个合格的BeanDefinition，也就不会扫描到
		for (AnnotationAttributes filter : componentScan.getAnnotationArray("includeFilters")) {
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				scanner.addIncludeFilter(typeFilter);
			}
		}
		//这个是看是否配置了不扫描的过滤器,类似上面的，这个过滤器就是表示是否排除在外的BeanDefinition，如果排除在外
		//那么如果你的BeanDefinition包含在里面，就不会认为是一个合格的BeanDefinition
		for (AnnotationAttributes filter : componentScan.getAnnotationArray("excludeFilters")) {
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				scanner.addExcludeFilter(typeFilter);
			}
		}

		//是否配置了延迟加载，这个是全局配置的延迟加载，如果配置了延迟加载的的属性，那么这个设置一个全局的延迟加载属性
		//到scanner中，后面会应用到当前扫描到的所有BeanDefinition
		boolean lazyInit = componentScan.getBoolean("lazyInit");
		if (lazyInit) {
			scanner.getBeanDefinitionDefaults().setLazyInit(true);
		}

		/**
		 * 下面的代码主要是为了拿到我们的配置的扫描包路径，非常简单，就是拿到我们配置的扫描包路径比如：com.xxxx.xxxx
		 */
		Set<String> basePackages = new LinkedHashSet<>();
		String[] basePackagesArray = componentScan.getStringArray("basePackages");
		for (String pkg : basePackagesArray) {
			String[] tokenized = StringUtils.tokenizeToStringArray(this.environment.resolvePlaceholders(pkg),
					ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
			Collections.addAll(basePackages, tokenized);
		}
		for (Class<?> clazz : componentScan.getClassArray("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}
		if (basePackages.isEmpty()) {
			basePackages.add(ClassUtils.getPackageName(declaringClass));
		}

		scanner.addExcludeFilter(new AbstractTypeHierarchyTraversingFilter(false, false) {
			@Override
			protected boolean matchClassName(String className) {
				return declaringClass.equals(className);
			}
		});
		//执行真正的扫描，包路径是一个数组
		return scanner.doScan(StringUtils.toStringArray(basePackages));
	}

	private List<TypeFilter> typeFiltersFor(AnnotationAttributes filterAttributes) {
		List<TypeFilter> typeFilters = new ArrayList<>();
		FilterType filterType = filterAttributes.getEnum("type");

		for (Class<?> filterClass : filterAttributes.getClassArray("classes")) {
			switch (filterType) {
				case ANNOTATION:
					Assert.isAssignable(Annotation.class, filterClass,
							"@ComponentScan ANNOTATION type filter requires an annotation type");
					@SuppressWarnings("unchecked")
					Class<Annotation> annotationType = (Class<Annotation>) filterClass;
					typeFilters.add(new AnnotationTypeFilter(annotationType));
					break;
				case ASSIGNABLE_TYPE:
					typeFilters.add(new AssignableTypeFilter(filterClass));
					break;
				case CUSTOM:
					Assert.isAssignable(TypeFilter.class, filterClass,
							"@ComponentScan CUSTOM type filter requires a TypeFilter implementation");

					TypeFilter filter = ParserStrategyUtils.instantiateClass(filterClass, TypeFilter.class,
							this.environment, this.resourceLoader, this.registry);
					typeFilters.add(filter);
					break;
				default:
					throw new IllegalArgumentException("Filter type not supported with Class value: " + filterType);
			}
		}

		for (String expression : filterAttributes.getStringArray("pattern")) {
			switch (filterType) {
				case ASPECTJ:
					typeFilters.add(new AspectJTypeFilter(expression, this.resourceLoader.getClassLoader()));
					break;
				case REGEX:
					typeFilters.add(new RegexPatternTypeFilter(Pattern.compile(expression)));
					break;
				default:
					throw new IllegalArgumentException("Filter type not supported with String pattern: " + filterType);
			}
		}

		return typeFilters;
	}

}
