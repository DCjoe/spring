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

package org.springframework.context.support;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.support.ResourceEditorRegistrar;
import org.springframework.context.*;
import org.springframework.context.event.*;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.context.weaving.LoadTimeWeaverAware;
import org.springframework.context.weaving.LoadTimeWeaverAwareProcessor;
import org.springframework.core.NativeDetector;
import org.springframework.core.ResolvableType;
import org.springframework.core.SpringProperties;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract implementation of the {@link org.springframework.context.ApplicationContext}
 * interface. Doesn't mandate the type of storage used for configuration; simply
 * implements common context functionality. Uses the Template Method design pattern,
 * requiring concrete subclasses to implement abstract methods.
 *
 * <p>In contrast to a plain BeanFactory, an ApplicationContext is supposed
 * to detect special beans defined in its internal bean factory:
 * Therefore, this class automatically registers
 * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor BeanFactoryPostProcessors},
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessors},
 * and {@link org.springframework.context.ApplicationListener ApplicationListeners}
 * which are defined as beans in the context.
 *
 * <p>A {@link org.springframework.context.MessageSource} may also be supplied
 * as a bean in the context, with the name "messageSource"; otherwise, message
 * resolution is delegated to the parent context. Furthermore, a multicaster
 * for application events can be supplied as an "applicationEventMulticaster" bean
 * of type {@link org.springframework.context.event.ApplicationEventMulticaster}
 * in the context; otherwise, a default multicaster of type
 * {@link org.springframework.context.event.SimpleApplicationEventMulticaster} will be used.
 *
 * <p>Implements resource loading by extending
 * {@link org.springframework.core.io.DefaultResourceLoader}.
 * Consequently treats non-URL resource paths as class path resources
 * (supporting full class path resource names that include the package path,
 * e.g. "mypackage/myresource.dat"), unless the {@link #getResourceByPath}
 * method is overridden in a subclass.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sam Brannen
 * @author Sebastien Deleuze
 * @author Brian Clozel
 * @since January 21, 2001
 * @see #refreshBeanFactory
 * @see #getBeanFactory
 * @see org.springframework.beans.factory.config.BeanFactoryPostProcessor
 * @see org.springframework.beans.factory.config.BeanPostProcessor
 * @see org.springframework.context.event.ApplicationEventMulticaster
 * @see org.springframework.context.ApplicationListener
 * @see org.springframework.context.MessageSource
 */
public abstract class AbstractApplicationContext extends DefaultResourceLoader
		implements ConfigurableApplicationContext {

	/**
	 * Name of the MessageSource bean in the factory.
	 * If none is supplied, message resolution is delegated to the parent.
	 * @see MessageSource
	 */
	public static final String MESSAGE_SOURCE_BEAN_NAME = "messageSource";

	/**
	 * Name of the LifecycleProcessor bean in the factory.
	 * If none is supplied, a DefaultLifecycleProcessor is used.
	 * @see org.springframework.context.LifecycleProcessor
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 */
	public static final String LIFECYCLE_PROCESSOR_BEAN_NAME = "lifecycleProcessor";

	/**
	 * Name of the ApplicationEventMulticaster bean in the factory.
	 * If none is supplied, a default SimpleApplicationEventMulticaster is used.
	 * @see org.springframework.context.event.ApplicationEventMulticaster
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 */
	public static final String APPLICATION_EVENT_MULTICASTER_BEAN_NAME = "applicationEventMulticaster";

	/**
	 * Boolean flag controlled by a {@code spring.spel.ignore} system property that instructs Spring to
	 * ignore SpEL, i.e. to not initialize the SpEL infrastructure.
	 * <p>The default is "false".
	 */
	private static final boolean shouldIgnoreSpel = SpringProperties.getFlag("spring.spel.ignore");


	static {
		// Eagerly load the ContextClosedEvent class to avoid weird classloader issues
		// on application shutdown in WebLogic 8.1. (Reported by Dustin Woods.)
		ContextClosedEvent.class.getName();
	}


	/** Logger used by this class. Available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Unique id for this context, if any. */
	private String id = ObjectUtils.identityToString(this);

	/** Display name. */
	private String displayName = ObjectUtils.identityToString(this);

	/** Parent context. */
	@Nullable
	private ApplicationContext parent;

	/** Environment used by this context. */
	@Nullable
	private ConfigurableEnvironment environment;

	/** BeanFactoryPostProcessors to apply on refresh. */
	private final List<BeanFactoryPostProcessor> beanFactoryPostProcessors = new ArrayList<>();

	/** System time in milliseconds when this context started. */
	private long startupDate;

	/** Flag that indicates whether this context is currently active. */
	private final AtomicBoolean active = new AtomicBoolean();

	/** Flag that indicates whether this context has been closed already. */
	private final AtomicBoolean closed = new AtomicBoolean();

	/** Synchronization monitor for the "refresh" and "destroy". */
	private final Object startupShutdownMonitor = new Object();

	/** Reference to the JVM shutdown hook, if registered. */
	@Nullable
	private Thread shutdownHook;

	/** ResourcePatternResolver used by this context. */
	private ResourcePatternResolver resourcePatternResolver;

	/** LifecycleProcessor for managing the lifecycle of beans within this context. */
	@Nullable
	private LifecycleProcessor lifecycleProcessor;

	/** MessageSource we delegate our implementation of this interface to. */
	@Nullable
	private MessageSource messageSource;

	/** Helper class used in event publishing. */
	@Nullable
	private ApplicationEventMulticaster applicationEventMulticaster;

	/** Application startup metrics. **/
	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;

	/** Statically specified listeners. */
	private final Set<ApplicationListener<?>> applicationListeners = new LinkedHashSet<>();

	/** Local listeners registered before refresh. */
	@Nullable
	private Set<ApplicationListener<?>> earlyApplicationListeners;

	/** ApplicationEvents published before the multicaster setup. */
	@Nullable
	private Set<ApplicationEvent> earlyApplicationEvents;


	/**
	 * Create a new AbstractApplicationContext with no parent.
	 */
	public AbstractApplicationContext() {
		this.resourcePatternResolver = getResourcePatternResolver();
	}

	/**
	 * Create a new AbstractApplicationContext with the given parent context.
	 * @param parent the parent context
	 */
	public AbstractApplicationContext(@Nullable ApplicationContext parent) {
		this();
		setParent(parent);
	}


	//---------------------------------------------------------------------
	// Implementation of ApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * Set the unique id of this application context.
	 * <p>Default is the object id of the context instance, or the name
	 * of the context bean if the context is itself defined as a bean.
	 * @param id the unique id of the context
	 */
	@Override
	public void setId(String id) {
		this.id = id;
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public String getApplicationName() {
		return "";
	}

	/**
	 * Set a friendly name for this context.
	 * Typically done during initialization of concrete context implementations.
	 * <p>Default is the object id of the context instance.
	 */
	public void setDisplayName(String displayName) {
		Assert.hasLength(displayName, "Display name must not be empty");
		this.displayName = displayName;
	}

	/**
	 * Return a friendly name for this context.
	 * @return a display name for this context (never {@code null})
	 */
	@Override
	public String getDisplayName() {
		return this.displayName;
	}

	/**
	 * Return the parent context, or {@code null} if there is no parent
	 * (that is, this context is the root of the context hierarchy).
	 */
	@Override
	@Nullable
	public ApplicationContext getParent() {
		return this.parent;
	}

	/**
	 * Set the {@code Environment} for this application context.
	 * <p>Default value is determined by {@link #createEnvironment()}. Replacing the
	 * default with this method is one option but configuration through {@link
	 * #getEnvironment()} should also be considered. In either case, such modifications
	 * should be performed <em>before</em> {@link #refresh()}.
	 * @see org.springframework.context.support.AbstractApplicationContext#createEnvironment
	 */
	@Override
	public void setEnvironment(ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	/**
	 * Return the {@code Environment} for this application context in configurable
	 * form, allowing for further customization.
	 * <p>If none specified, a default environment will be initialized via
	 * {@link #createEnvironment()}.
	 */
	@Override
	public ConfigurableEnvironment getEnvironment() {
		if (this.environment == null) {
			this.environment = createEnvironment();
		}
		return this.environment;
	}

	/**
	 * Create and return a new {@link StandardEnvironment}.
	 * <p>Subclasses may override this method in order to supply
	 * a custom {@link ConfigurableEnvironment} implementation.
	 */
	protected ConfigurableEnvironment createEnvironment() {
		return new StandardEnvironment();
	}

	/**
	 * Return this context's internal bean factory as AutowireCapableBeanFactory,
	 * if already available.
	 * @see #getBeanFactory()
	 */
	@Override
	public AutowireCapableBeanFactory getAutowireCapableBeanFactory() throws IllegalStateException {
		return getBeanFactory();
	}

	/**
	 * Return the timestamp (ms) when this context was first loaded.
	 */
	@Override
	public long getStartupDate() {
		return this.startupDate;
	}

	/**
	 * Publish the given event to all listeners.
	 * <p>Note: Listeners get initialized after the MessageSource, to be able
	 * to access it within listener implementations. Thus, MessageSource
	 * implementations cannot publish events.
	 * @param event the event to publish (may be application-specific or a
	 * standard framework event)
	 */
	@Override
	public void publishEvent(ApplicationEvent event) {
		publishEvent(event, null);
	}

	/**
	 * Publish the given event to all listeners.
	 * <p>Note: Listeners get initialized after the MessageSource, to be able
	 * to access it within listener implementations. Thus, MessageSource
	 * implementations cannot publish events.
	 * @param event the event to publish (may be an {@link ApplicationEvent}
	 * or a payload object to be turned into a {@link PayloadApplicationEvent})
	 */
	@Override
	public void publishEvent(Object event) {
		publishEvent(event, null);
	}

	/**
	 * Publish the given event to all listeners.
	 * @param event the event to publish (may be an {@link ApplicationEvent}
	 * or a payload object to be turned into a {@link PayloadApplicationEvent})
	 * @param eventType the resolved event type, if known
	 * @since 4.2
	 */
	protected void publishEvent(Object event, @Nullable ResolvableType eventType) {
		Assert.notNull(event, "Event must not be null");

		// Decorate event as an ApplicationEvent if necessary
		ApplicationEvent applicationEvent;
		if (event instanceof ApplicationEvent) {
			applicationEvent = (ApplicationEvent) event;
		}
		else {
			applicationEvent = new PayloadApplicationEvent<>(this, event);
			if (eventType == null) {
				eventType = ((PayloadApplicationEvent<?>) applicationEvent).getResolvableType();
			}
		}

		// Multicast right now if possible - or lazily once the multicaster is initialized
		if (this.earlyApplicationEvents != null) {
			this.earlyApplicationEvents.add(applicationEvent);
		}
		else {
			getApplicationEventMulticaster().multicastEvent(applicationEvent, eventType);
		}

		// Publish event via parent context as well...
		if (this.parent != null) {
			if (this.parent instanceof AbstractApplicationContext) {
				((AbstractApplicationContext) this.parent).publishEvent(event, eventType);
			}
			else {
				this.parent.publishEvent(event);
			}
		}
	}

	/**
	 * Return the internal ApplicationEventMulticaster used by the context.
	 * @return the internal ApplicationEventMulticaster (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	ApplicationEventMulticaster getApplicationEventMulticaster() throws IllegalStateException {
		if (this.applicationEventMulticaster == null) {
			throw new IllegalStateException("ApplicationEventMulticaster not initialized - " +
					"call 'refresh' before multicasting events via the context: " + this);
		}
		return this.applicationEventMulticaster;
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		Assert.notNull(applicationStartup, "applicationStartup should not be null");
		this.applicationStartup = applicationStartup;
	}

	@Override
	public ApplicationStartup getApplicationStartup() {
		return this.applicationStartup;
	}

	/**
	 * Return the internal LifecycleProcessor used by the context.
	 * @return the internal LifecycleProcessor (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	LifecycleProcessor getLifecycleProcessor() throws IllegalStateException {
		if (this.lifecycleProcessor == null) {
			throw new IllegalStateException("LifecycleProcessor not initialized - " +
					"call 'refresh' before invoking lifecycle methods via the context: " + this);
		}
		return this.lifecycleProcessor;
	}

	/**
	 * Return the ResourcePatternResolver to use for resolving location patterns
	 * into Resource instances. Default is a
	 * {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver},
	 * supporting Ant-style location patterns.
	 * <p>Can be overridden in subclasses, for extended resolution strategies,
	 * for example in a web environment.
	 * <p><b>Do not call this when needing to resolve a location pattern.</b>
	 * Call the context's {@code getResources} method instead, which
	 * will delegate to the ResourcePatternResolver.
	 * @return the ResourcePatternResolver for this context
	 * @see #getResources
	 * @see org.springframework.core.io.support.PathMatchingResourcePatternResolver
	 */
	protected ResourcePatternResolver getResourcePatternResolver() {
		return new PathMatchingResourcePatternResolver(this);
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * Set the parent of this application context.
	 * <p>The parent {@linkplain ApplicationContext#getEnvironment() environment} is
	 * {@linkplain ConfigurableEnvironment#merge(ConfigurableEnvironment) merged} with
	 * this (child) application context environment if the parent is non-{@code null} and
	 * its environment is an instance of {@link ConfigurableEnvironment}.
	 * @see ConfigurableEnvironment#merge(ConfigurableEnvironment)
	 */
	@Override
	public void setParent(@Nullable ApplicationContext parent) {
		this.parent = parent;
		if (parent != null) {
			Environment parentEnvironment = parent.getEnvironment();
			if (parentEnvironment instanceof ConfigurableEnvironment) {
				getEnvironment().merge((ConfigurableEnvironment) parentEnvironment);
			}
		}
	}

	@Override
	public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
		Assert.notNull(postProcessor, "BeanFactoryPostProcessor must not be null");
		this.beanFactoryPostProcessors.add(postProcessor);
	}

	/**
	 * Return the list of BeanFactoryPostProcessors that will get applied
	 * to the internal BeanFactory.
	 */
	public List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
		return this.beanFactoryPostProcessors;
	}

	@Override
	public void addApplicationListener(ApplicationListener<?> listener) {
		Assert.notNull(listener, "ApplicationListener must not be null");
		if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.addApplicationListener(listener);
		}
		this.applicationListeners.add(listener);
	}

	/**
	 * Return the list of statically specified ApplicationListeners.
	 */
	public Collection<ApplicationListener<?>> getApplicationListeners() {
		return this.applicationListeners;
	}

	@Override
	public void refresh() throws BeansException, IllegalStateException {
		synchronized (this.startupShutdownMonitor) {
			StartupStep contextRefresh = this.applicationStartup.start("spring.context.refresh");

			// Prepare this context for refreshing.
			//容器启动前准备工作，也就是设置容器当前的状态和记录启动开始时间以及初始化资源数据以及验证下我们需要验证的一些资源key是否存在
			prepareRefresh();

			// Tell the subclass to refresh the internal bean factory.
			/**获取一个默认的工厂，这个工厂在我们的构造实例化的时候就一个创建了一个默认的工厂
			 * 这个工厂非常重要，我们spring的执行的开始阶段是先暴露一个工厂，这个工厂里面包括了spring
			 * 之后执行的所有东西，其中我们的加了组件注解的，比如@Component @Repository等都会被扫描到
			 * 而扫描到的类，目前还不是Bean，spring扫描到过后将它们变成Bd，一般是AnnotatedBeanDefinition
			 * 也就是注解BD,然后将这个BD放入 默认工厂DefaultListableBeanFactory中的bd map
			 */
			ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

			// Prepare the bean factory for use in this context.
			/**
			 * 这里是准备工厂：
			 * 1.设置BeanDefinition的类加载器
			 * 2.设置spring容器默认的类型转换器
			 * 3.设置spring解析el表达式的解析器
			 * 4.添加一个Bean的后置处理器ApplicationContextAwareProcessor
			 * 5.将bean工厂的一些类，比如ApplicationContext直接注册到单例池中
			 * 6.去除一些在byType或者byName的时候需要过滤掉的一些bean（spring在依赖注入的时候会先在这些默认注册的bean中进行byType找
			 * 如果找到了，就加入到列表中，简单来说就是比如你在bean中依赖注入了ApplicationContext context,那么spring会把默认注册的这些bean
			 * 中找到然后进行注册）。
			 * 7.将系统的环境信息、spring容器的启动环境信息、操作系统的环境信息直接注册成一个单例的bean
			 */
			prepareBeanFactory(beanFactory);

			try {
				// Allows post-processing of the bean factory in context subclasses.
				// 模板方法，此时，所有的beanDefinition已经加载，但是还没有实例化。
				//允许在子类中对beanFactory进行扩展处理。比如添加ware相关接口自动装配设置，添加后置处理器等，是子类扩展prepareBeanFactory(beanFactory)的方法
				postProcessBeanFactory(beanFactory);

				StartupStep beanPostProcess = this.applicationStartup.start("spring.context.beans.post-process");
				// Invoke factory processors registered as beans in the context.
				//这里就是调用后置处理器，程序执行到这里为止，还没有添加的有我们用户自定义的后置处理器，但是
				//spring添加了自己默认的后置处理器，比如ConfigurationClassBeanFactory，这个类用来解析和扫描
				//应用组件，比如加了@Component或者相对的其他组件的类，将它们转成bd放入工厂的bd map中
				//这里做的事情有：
				// 1.将我们标记为容器单例类扫描成bd放入bd map
				// 2.处理@Import注解
				//3.如果我们的配置类是@Configuration的，那么会生成这个配置类的CGLIB代理类，如果没有加@Configuration，则就是一个普通Bean
				invokeBeanFactoryPostProcessors(beanFactory);

				// Register bean processors that intercept bean creation.
				/**
				 * 上面的一个方法invokeBeanFactoryPostProcessors是将我们系统中所有符合条件的普通类都扫描成了一个BeanDefinition
				 * 并且放入到了beanDefinitionMap中，包括业务的bean，bean的后置处理器、bean工厂的后置处理器等等
				 * 也就是说所有的BeanDefinition都已经扫描完成了，下面这个方法做的事情就是从beanDefinitionMap中
				 * 取出bean的后置处理器然后 放入到后置处理器的缓存列表中
				 * 当然了，这里不仅仅去取出后置处理器，还进行了一些排序，具体怎么排序，可以看下里面的操作
				 */
				// 实例化和注册beanFactory中扩展了BeanPostProcessor的bean
				//例如：
				// AutowiredAnnotationBeanPostProcessor(处理被@Autowired注解修饰的bean并注入)
				// RequiredAnnotationBeanPostProcessor(处理被@Required注解修饰的方法)
				// CommonAnnotationBeanPostProcessor(处理@PreDestroy、@PostConstruct、@Resource等多个注解的作用)等。
				registerBeanPostProcessors(beanFactory);
				beanPostProcess.end();

				// Initialize message source for this context.
				initMessageSource();

				// Initialize event multicaster for this context.
				initApplicationEventMulticaster();

				// Initialize other special beans in specific context subclasses.
				// 模板方法，在容器刷新的时候可以自定义逻辑，不同的Spring容器做不同的事情。
				onRefresh();

				// Check for listener beans and register them.
				//将容器中和BeanDefinitionMap中的监听器添加到事件监听器中
				registerListeners();

				// Instantiate all remaining (non-lazy-init) singletons.
				// 实例化所有剩余的（非懒加载）单例
				// 比如invokeBeanFactoryPostProcessors方法中根据各种注解解析出来的类，在这个时候都会被初始化。
				// 实例化的过程各种BeanPostProcessor开始起作用。
				finishBeanFactoryInitialization(beanFactory);

				// Last step: publish corresponding event.
				// refresh做完之后需要做的其他事情。
				// 清除上下文资源缓存（如扫描中的ASM元数据）
				// 初始化上下文的生命周期处理器，并刷新（找出Spring容器中实现了Lifecycle接口的bean并执行start()方法）。
				// 发布ContextRefreshedEvent事件告知对应的ApplicationListener进行响应的操作
				finishRefresh();
			}

			catch (BeansException ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Exception encountered during context initialization - " +
							"cancelling refresh attempt: " + ex);
				}

				// Destroy already created singletons to avoid dangling resources.
				destroyBeans();

				// Reset 'active' flag.
				cancelRefresh(ex);

				// Propagate exception to caller.
				throw ex;
			}

			finally {
				// Reset common introspection caches in Spring's core, since we
				// might not ever need metadata for singleton beans anymore...
				resetCommonCaches();
				contextRefresh.end();
			}
		}
	}

	/**
	 * Prepare this context for refreshing, setting its startup date and
	 * active flag as well as performing any initialization of property sources.
	 */
	protected void prepareRefresh() {
		// Switch to active.
		//记录启动时间
		this.startupDate = System.currentTimeMillis();
		//设置当前容器时激活状态
		this.closed.set(false);
		this.active.set(true);

		if (logger.isDebugEnabled()) {
			if (logger.isTraceEnabled()) {
				logger.trace("Refreshing " + this);
			}
			else {
				logger.debug("Refreshing " + getDisplayName());
			}
		}

		// Initialize any placeholder property sources in the context environment.
		//初始化property资源文件，spring这里没有实现，是留给子类去实现了，如果你定义了一个启动器，那么你可以去实现自己的加载资源的逻辑
		initPropertySources();

		// Validate that all properties marked as required are resolvable:
		// see ConfigurablePropertyResolver#setRequiredProperties
		//这里是验证现在系统资源中必须要存在哪些资源key，也就是说现在的环境中，必须要存在哪些key才能进行启动容器的意思
		//就是验证资源的key是否在环境中存在的意思
		getEnvironment().validateRequiredProperties();

		//初始化监听器，如果这个监听器不为空，然后清空监听器
		// Store pre-refresh ApplicationListeners...
		if (this.earlyApplicationListeners == null) {
			this.earlyApplicationListeners = new LinkedHashSet<>(this.applicationListeners);
		}
		else {
			// Reset local application listeners to pre-refresh state.
			this.applicationListeners.clear();
			this.applicationListeners.addAll(this.earlyApplicationListeners);
		}

		// Allow for the collection of early ApplicationEvents,
		// to be published once the multicaster is available...
		this.earlyApplicationEvents = new LinkedHashSet<>();
	}

	/**
	 * <p>Replace any stub property sources with actual instances.
	 * @see org.springframework.core.env.PropertySource.StubPropertySource
	 * @see org.springframework.web.context.support.WebApplicationContextUtils#initServletPropertySources
	 */
	protected void initPropertySources() {
		// For subclasses: do nothing by default.
	}

	/**
	 * Tell the subclass to refresh the internal bean factory.
	 * @return the fresh BeanFactory instance
	 * @see #refreshBeanFactory()
	 * @see #getBeanFactory()
	 */
	protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
		refreshBeanFactory();
		return getBeanFactory();
	}

	/**
	 * Configure the factory's standard context characteristics,
	 * such as the context's ClassLoader and post-processors.
	 * @param beanFactory the BeanFactory to configure
	 */
	protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// Tell the internal bean factory to use the context's class loader etc.
		//添加一个类加载器，这个类加载器的用处在那里呢？spring扫描类的时候是通过ASM字节码技术扫描的，这个时候这个class是没有被加载的
		//只是在硬盘上的一个class文件而已，扫描到过后是放入了BeanDefinition的beanClass属性中，只是一个类的全限定名，
		//这里设置类加载器的意思就是后面初始化bean的时候会将这个beanClass的全限定名拿出来然后加载到jvm，这个时候就需要一个类加载器
		//所以这里设置一个了加载器
		beanFactory.setBeanClassLoader(getClassLoader());
		if (!shouldIgnoreSpel) {
			//添加一个处理el表达式的解析器，比如我们的@Value()中就有可能是表达式，需要解析$和#,$是一个上下文参数的占位符
			//而#是el表达式
			beanFactory.setBeanExpressionResolver(new StandardBeanExpressionResolver(beanFactory.getBeanClassLoader()));
		}
		//注册一些类型的转换器，比如我们在类中定义了
		/**
		 * @Value("c://xxx/tt.file")
		 * private File file;
		 * 那么这里设置的类型转换器就是讲@Value中的字符串直接生成一个File，还有类似于Integer  xxx一样的意思
		 */
		beanFactory.addPropertyEditorRegistrar(new ResourceEditorRegistrar(this, getEnvironment()));

		// Configure the bean factory with context callbacks.
		/**
		 * 这里添加一个ApplicationContextAwareProcessor，这个类就是Application，我们的一个普通类型如果实现了这个接口
		 * 那么这个接口会提供一个默认的回调方法，会将Application对象给你，你拿到这个对象过后就可以对bean工厂进行操作
		 * ApplicationContextAwareProcessor是一个bean的后置处理器，是在bean的初始化前进行调用的postProcessBeforeInitialization
		 * 主要是一些Aware的回调，这个aware不是spring默认的aware，默认的aware是在初始的方法里面调用的
		 * 这里的ApplicationContextAwareProcessor主要处理了如果实现了
		 * EnvironmentAware
		 * EmbeddedValueResolverAware
		 * ResourceLoaderAware
		 * ApplicationEventPublisherAware
		 * MessageSourceAware
		 * ApplicationContextAware
		 * 那么它会在bean的实例化前来调用这些aware的回调方法
		 */
		beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this));
		/**
		 * 下面的ignoreDependencyInterface就是针对上面的添加的bean后置处理器ApplicationContextAwareProcessor的一个补充
		 * 就是说如果我们定义的bean是单独的byType或者byName的形式，这个在xml中或者@Bean会有设置，一般的使用@Component是不会
		 * 只有byType或者byName的，@component是先byType，再经过一系列的筛查，再byName，所以在xml中或者@Bean中设置了bytype或者byname的
		 * 时候，要过滤下面的这些aware的依赖注入的调用，比如
		 *
		 * public   xxx implements Applicationaware{
		 *
		 *     @AutoWired
		 * 	   @Ovvierd
		 *     public void setApplicationContext(Application contet){
		 *
		 *     }
		 *
		 * }
		 * 首先如果加了@AutoWired，依赖注入的时候会扫描到进行调用，而本身ApplicationContextAwareProcessor又是一个bean的初始化前
		 * 的后置处理器，所以针对@AutoWired会调用两次，而xml或者@Bean中可能只会调用一次，而真正的实际开发过程中
		 * 我们不会在一个回调的方法中加一个@AutoWired，只是这里把这个概念说清楚；
		 * 所以下面的ignoreDependencyInterface将这些aware类添加到ignoredDependencyInterfaces缓存中
		 * 表示在byname或者bytype的时候自动过滤掉，不需要进行调用，它是bean的一个后置处理器，会在bean的后置处理器中进行调用
		 * 就是这个意思
		 *
		 */
		beanFactory.ignoreDependencyInterface(EnvironmentAware.class);
		beanFactory.ignoreDependencyInterface(EmbeddedValueResolverAware.class);
		beanFactory.ignoreDependencyInterface(ResourceLoaderAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationEventPublisherAware.class);
		beanFactory.ignoreDependencyInterface(MessageSourceAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationContextAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationStartupAware.class);

		// BeanFactory interface not registered as resolvable type in a plain factory.
		// MessageSource registered (and found for autowiring) as a bean.
		/**
		 * spring将
		 * BeanFactory
		 * ResourceLoader
		 * ApplicationEventPublisher
		 * ApplicationContext
		 * 都注册到一个依赖的缓存中，其实就是一个单例池一样的，然后我们的依赖注入的核心方法
		 * findAutowireCandidates，就是在@AutoWired依赖注入的时候筛选bean的时候需要先byType，第一次就是从这个缓存
		 *resolvableDependencies中先去获取的，也就是说你在普通额bean中注入了一个private BeanFactory beanFactory，那么
		 * spring容器会给你直接从resolvableDependencies中找到然后直接给你注入
		 */
		beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory);
		beanFactory.registerResolvableDependency(ResourceLoader.class, this);
		beanFactory.registerResolvableDependency(ApplicationEventPublisher.class, this);
		beanFactory.registerResolvableDependency(ApplicationContext.class, this);

		// Register early post-processor for detecting inner beans as ApplicationListeners.
		/**
		 * 最后添加一个bean的后置处理器，这个后置处理器在bean初始化后会调用postProcessAfterInitialization
		 * 这个后置处理器在缓存中是属于最后一个，它的作用就是把实现了ApplicationListener的添加是监听器中
		 */
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(this));

		// Detect a LoadTimeWeaver and prepare for weaving, if found.
		if (!NativeDetector.inNativeImage() && beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
			// Set a temporary ClassLoader for type matching.
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
		}

		// Register default environment beans.
		//将environment直接注册成一个单例bean，environment是系统的环境变量信息
		if (!beanFactory.containsLocalBean(ENVIRONMENT_BEAN_NAME)) {
			beanFactory.registerSingleton(ENVIRONMENT_BEAN_NAME, getEnvironment());
		}
		//将系统中的属性配置信息注册一个bean，bean的名字就是systemProperties
		if (!beanFactory.containsLocalBean(SYSTEM_PROPERTIES_BEAN_NAME)) {
			beanFactory.registerSingleton(SYSTEM_PROPERTIES_BEAN_NAME, getEnvironment().getSystemProperties());
		}
		//将系统的环境变量注册成一个单例bean，systemEnvironment
		if (!beanFactory.containsLocalBean(SYSTEM_ENVIRONMENT_BEAN_NAME)) {
			beanFactory.registerSingleton(SYSTEM_ENVIRONMENT_BEAN_NAME, getEnvironment().getSystemEnvironment());
		}
		if (!beanFactory.containsLocalBean(APPLICATION_STARTUP_BEAN_NAME)) {
			beanFactory.registerSingleton(APPLICATION_STARTUP_BEAN_NAME, getApplicationStartup());
		}
	}

	/**
	 * Modify the application context's internal bean factory after its standard
	 * initialization. All bean definitions will have been loaded, but no beans
	 * will have been instantiated yet. This allows for registering special
	 * BeanPostProcessors etc in certain ApplicationContext implementations.
	 * @param beanFactory the bean factory used by the application context
	 */
	protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
	}

	/**
	 * Instantiate and invoke all registered BeanFactoryPostProcessor beans,
	 * respecting explicit order if given.
	 * <p>Must be called before singleton instantiation.
	 */
	protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		/**
		 * 这里是调用工厂bean的后置处理器，bean工厂的后置处理器有两个
		 * BeanFactoryPostProcessor和子类BeanDefinitionRegistryPostProcessor
		 * 下面的方法主要是处理这两个后置处理器，其实spring的设计人员的核心思想是：
		 * 1.bean工厂中的后置处理器分为了用户自定义的和spring内置的后置处理器，用户自定义的后置处理器分为手动添加的和扫描得到的
		 * 如果手动添加的，那么先执行手动添加的后置处理器；
		 * 2.如果是扫描得到的和spring内部的后置处理器是根据优先级来进行执行的，但是不管哪种情况的bean工厂后置处理器都是
		 * 先执行BeanDefinitionRegistryPostProcessor中的postProcessBeanDefinitionRegistry，再执行父类中postProcessBeanFactory
		 * 为什么这样做的，因为postProcessBeanDefinitionRegistry可以注册BeanDefinition，而postProcessBeanFactory不能注册
		 * BeanDefinition，也就是说先把所有的能注册BeanDefinition的所有bean工厂后置处理器处理完成，才来对bean工厂中的
		 * BeanDefinition进行统一处理
		 * getBeanFactoryPostProcessors()这个方法获取的是用户手动添加的bean工厂后置处理器，如果你在启动的时候没有手动添加后置处理器
		 * 那么这个方法获取的为空
		 *
		 */
		PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());

		// Detect a LoadTimeWeaver and prepare for weaving, if found in the meantime
		// (e.g. through an @Bean method registered by ConfigurationClassPostProcessor)
		if (!NativeDetector.inNativeImage() && beanFactory.getTempClassLoader() == null && beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
		}
	}

	/**
	 * Instantiate and register all BeanPostProcessor beans,
	 * respecting explicit order if given.
	 * <p>Must be called before any instantiation of application beans.
	 */
	protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		PostProcessorRegistrationDelegate.registerBeanPostProcessors(beanFactory, this);
	}

	/**
	 * Initialize the MessageSource.
	 * Use parent's if none defined in this context.
	 */
	protected void initMessageSource() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		if (beanFactory.containsLocalBean(MESSAGE_SOURCE_BEAN_NAME)) {
			this.messageSource = beanFactory.getBean(MESSAGE_SOURCE_BEAN_NAME, MessageSource.class);
			// Make MessageSource aware of parent MessageSource.
			if (this.parent != null && this.messageSource instanceof HierarchicalMessageSource) {
				HierarchicalMessageSource hms = (HierarchicalMessageSource) this.messageSource;
				if (hms.getParentMessageSource() == null) {
					// Only set parent context as parent MessageSource if no parent MessageSource
					// registered already.
					hms.setParentMessageSource(getInternalParentMessageSource());
				}
			}
			if (logger.isTraceEnabled()) {
				logger.trace("Using MessageSource [" + this.messageSource + "]");
			}
		}
		else {
			// Use empty MessageSource to be able to accept getMessage calls.
			DelegatingMessageSource dms = new DelegatingMessageSource();
			dms.setParentMessageSource(getInternalParentMessageSource());
			this.messageSource = dms;
			beanFactory.registerSingleton(MESSAGE_SOURCE_BEAN_NAME, this.messageSource);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + MESSAGE_SOURCE_BEAN_NAME + "' bean, using [" + this.messageSource + "]");
			}
		}
	}

	/**
	 * Initialize the ApplicationEventMulticaster.
	 * Uses SimpleApplicationEventMulticaster if none defined in the context.
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 */
	protected void initApplicationEventMulticaster() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
			this.applicationEventMulticaster =
					beanFactory.getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class);
			if (logger.isTraceEnabled()) {
				logger.trace("Using ApplicationEventMulticaster [" + this.applicationEventMulticaster + "]");
			}
		}
		else {
			this.applicationEventMulticaster = new SimpleApplicationEventMulticaster(beanFactory);
			beanFactory.registerSingleton(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, this.applicationEventMulticaster);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + APPLICATION_EVENT_MULTICASTER_BEAN_NAME + "' bean, using " +
						"[" + this.applicationEventMulticaster.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * Initialize the LifecycleProcessor.
	 * Uses DefaultLifecycleProcessor if none defined in the context.
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 * 判断容器中是否有个一个bean lifecycleProcessor，如果没有，就添加一个默认的lifecycleProcessor bean  DefaultLifecycleProcessor
	 */
	protected void initLifecycleProcessor() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		if (beanFactory.containsLocalBean(LIFECYCLE_PROCESSOR_BEAN_NAME)) {
			this.lifecycleProcessor =
					beanFactory.getBean(LIFECYCLE_PROCESSOR_BEAN_NAME, LifecycleProcessor.class);
			if (logger.isTraceEnabled()) {
				logger.trace("Using LifecycleProcessor [" + this.lifecycleProcessor + "]");
			}
		}
		else {
			DefaultLifecycleProcessor defaultProcessor = new DefaultLifecycleProcessor();
			defaultProcessor.setBeanFactory(beanFactory);
			this.lifecycleProcessor = defaultProcessor;
			beanFactory.registerSingleton(LIFECYCLE_PROCESSOR_BEAN_NAME, this.lifecycleProcessor);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + LIFECYCLE_PROCESSOR_BEAN_NAME + "' bean, using " +
						"[" + this.lifecycleProcessor.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * Template method which can be overridden to add context-specific refresh work.
	 * Called on initialization of special beans, before instantiation of singletons.
	 * <p>This implementation is empty.
	 * @throws BeansException in case of errors
	 * @see #refresh()
	 */
	protected void onRefresh() throws BeansException {
		// For subclasses: do nothing by default.
	}

	/**
	 * Add beans that implement ApplicationListener as listeners.
	 * Doesn't affect other listeners, which can be added without being beans.
	 */
	protected void registerListeners() {
		// Register statically specified listeners first.
		//这里是扫描完成过后，注册了时间发布器过后，这里把系统中所有的ApplicationListener添加到时间发布器中
		for (ApplicationListener<?> listener : getApplicationListeners()) {
			getApplicationEventMulticaster().addApplicationListener(listener);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let post-processors apply to them!
		/**
		 * 上面是添加了已经实现了ApplicationListener接口的bean添加到事件发布器中
		 * 但是下面又在获取ApplicationListener，是byType获取的，为什么这里还要去获取
		 * 因为容器中可能有FactoryBean，我们知道FactoryBean中的bean是需要真正调用的时候也就是getObject
		 * 才会返回具体的对象，估factoryBean默认是懒加载的，所以这里的ByType是获取BeanDefinition中factoryBean
		 * 类型是ApplicationListener的取出来然后添加到事件发布器的applicationListenerBeans中
		 */
		String[] listenerBeanNames = getBeanNamesForType(ApplicationListener.class, true, false);
		for (String listenerBeanName : listenerBeanNames) {
			getApplicationEventMulticaster().addApplicationListenerBean(listenerBeanName);
		}

		// Publish early application events now that we finally have a multicaster...
		Set<ApplicationEvent> earlyEventsToProcess = this.earlyApplicationEvents;
		this.earlyApplicationEvents = null;
		if (!CollectionUtils.isEmpty(earlyEventsToProcess)) {
			for (ApplicationEvent earlyEvent : earlyEventsToProcess) {
				getApplicationEventMulticaster().multicastEvent(earlyEvent);
			}
		}
	}

	/**
	 * Finish the initialization of this context's bean factory,
	 * initializing all remaining singleton beans.
	 */
	protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
		// Initialize conversion service for this context.
		if (beanFactory.containsBean(CONVERSION_SERVICE_BEAN_NAME) &&
				beanFactory.isTypeMatch(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class)) {
			beanFactory.setConversionService(
					beanFactory.getBean(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class));
		}

		// Register a default embedded value resolver if no BeanFactoryPostProcessor
		// (such as a PropertySourcesPlaceholderConfigurer bean) registered any before:
		// at this point, primarily for resolution in annotation attribute values.
		if (!beanFactory.hasEmbeddedValueResolver()) {
			beanFactory.addEmbeddedValueResolver(strVal -> getEnvironment().resolvePlaceholders(strVal));
		}

		// Initialize LoadTimeWeaverAware beans early to allow for registering their transformers early.
		String[] weaverAwareNames = beanFactory.getBeanNamesForType(LoadTimeWeaverAware.class, false, false);
		for (String weaverAwareName : weaverAwareNames) {
			getBean(weaverAwareName);
		}

		// Stop using the temporary ClassLoader for type matching.
		beanFactory.setTempClassLoader(null);

		// Allow for caching all bean definition metadata, not expecting further changes.
		beanFactory.freezeConfiguration();

		// Instantiate all remaining (non-lazy-init) singletons.
		beanFactory.preInstantiateSingletons();
	}

	/**
	 * Finish the refresh of this context, invoking the LifecycleProcessor's
	 * onRefresh() method and publishing the
	 * {@link org.springframework.context.event.ContextRefreshedEvent}.
	 */
	@SuppressWarnings("deprecation")
	protected void finishRefresh() {
		// Clear context-level resource caches (such as ASM metadata from scanning).
		clearResourceCaches();

		// Initialize lifecycle processor for this context.
		/**
		 * 初始化容器上下文生命周期，
		 * 也就是说容器启动完毕过后，提供了一个扩展点，就是在容器初始化完毕过后，会获取容器中是LiftCycle的类型bean
		 * 然后取出来执行里面的start方法，容器停止的时候会调用lifeCycle的stop方法
		 */
		initLifecycleProcessor();

		// Propagate refresh to lifecycle processor first.
		//初始化完成容器的LifeCycle过后，开始调用onRefresh
		getLifecycleProcessor().onRefresh();

		// Publish the final event.
		//发布一个容器刷新完毕的事件
		publishEvent(new ContextRefreshedEvent(this));

		// Participate in LiveBeansView MBean, if active.
		if (!NativeDetector.inNativeImage()) {
			LiveBeansView.registerApplicationContext(this);
		}
	}

	/**
	 * Cancel this context's refresh attempt, resetting the {@code active} flag
	 * after an exception got thrown.
	 * @param ex the exception that led to the cancellation
	 */
	protected void cancelRefresh(BeansException ex) {
		this.active.set(false);
	}

	/**
	 * Reset Spring's common reflection metadata caches, in particular the
	 * {@link ReflectionUtils}, {@link AnnotationUtils}, {@link ResolvableType}
	 * and {@link CachedIntrospectionResults} caches.
	 * @since 4.2
	 * @see ReflectionUtils#clearCache()
	 * @see AnnotationUtils#clearCache()
	 * @see ResolvableType#clearCache()
	 * @see CachedIntrospectionResults#clearClassLoader(ClassLoader)
	 */
	protected void resetCommonCaches() {
		ReflectionUtils.clearCache();
		AnnotationUtils.clearCache();
		ResolvableType.clearCache();
		CachedIntrospectionResults.clearClassLoader(getClassLoader());
	}


	/**
	 * Register a shutdown hook {@linkplain Thread#getName() named}
	 * {@code SpringContextShutdownHook} with the JVM runtime, closing this
	 * context on JVM shutdown unless it has already been closed at that time.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 * @see Runtime#addShutdownHook
	 * @see ConfigurableApplicationContext#SHUTDOWN_HOOK_THREAD_NAME
	 * @see #close()
	 * @see #doClose()
	 */
	@Override
	public void registerShutdownHook() {
		if (this.shutdownHook == null) {
			// No shutdown hook registered yet.
			this.shutdownHook = new Thread(SHUTDOWN_HOOK_THREAD_NAME) {
				@Override
				public void run() {
					synchronized (startupShutdownMonitor) {
						doClose();
					}
				}
			};
			Runtime.getRuntime().addShutdownHook(this.shutdownHook);
		}
	}

	/**
	 * Callback for destruction of this instance, originally attached
	 * to a {@code DisposableBean} implementation (not anymore in 5.0).
	 * <p>The {@link #close()} method is the native way to shut down
	 * an ApplicationContext, which this method simply delegates to.
	 * @deprecated as of Spring Framework 5.0, in favor of {@link #close()}
	 */
	@Deprecated
	public void destroy() {
		close();
	}

	/**
	 * Close this application context, destroying all beans in its bean factory.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 * Also removes a JVM shutdown hook, if registered, as it's not needed anymore.
	 * @see #doClose()
	 * @see #registerShutdownHook()
	 */
	@Override
	public void close() {
		synchronized (this.startupShutdownMonitor) {
			doClose();
			// If we registered a JVM shutdown hook, we don't need it anymore now:
			// We've already explicitly closed the context.
			if (this.shutdownHook != null) {
				try {
					Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
				}
				catch (IllegalStateException ex) {
					// ignore - VM is already shutting down
				}
			}
		}
	}

	/**
	 * Actually performs context closing: publishes a ContextClosedEvent and
	 * destroys the singletons in the bean factory of this application context.
	 * <p>Called by both {@code close()} and a JVM shutdown hook, if any.
	 * @see org.springframework.context.event.ContextClosedEvent
	 * @see #destroyBeans()
	 * @see #close()
	 * @see #registerShutdownHook()
	 */
	@SuppressWarnings("deprecation")
	protected void doClose() {
		// Check whether an actual close attempt is necessary...
		if (this.active.get() && this.closed.compareAndSet(false, true)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Closing " + this);
			}

			if (!NativeDetector.inNativeImage()) {
				LiveBeansView.unregisterApplicationContext(this);
			}

			try {
				// Publish shutdown event.
				publishEvent(new ContextClosedEvent(this));
			}
			catch (Throwable ex) {
				logger.warn("Exception thrown from ApplicationListener handling ContextClosedEvent", ex);
			}

			// Stop all Lifecycle beans, to avoid delays during individual destruction.
			if (this.lifecycleProcessor != null) {
				try {
					this.lifecycleProcessor.onClose();
				}
				catch (Throwable ex) {
					logger.warn("Exception thrown from LifecycleProcessor on context close", ex);
				}
			}

			// Destroy all cached singletons in the context's BeanFactory.
			destroyBeans();

			// Close the state of this context itself.
			closeBeanFactory();

			// Let subclasses do some final clean-up if they wish...
			onClose();

			// Reset local application listeners to pre-refresh state.
			if (this.earlyApplicationListeners != null) {
				this.applicationListeners.clear();
				this.applicationListeners.addAll(this.earlyApplicationListeners);
			}

			// Switch to inactive.
			this.active.set(false);
		}
	}

	/**
	 * Template method for destroying all beans that this context manages.
	 * The default implementation destroy all cached singletons in this context,
	 * invoking {@code DisposableBean.destroy()} and/or the specified
	 * "destroy-method".
	 * <p>Can be overridden to add context-specific bean destruction steps
	 * right before or right after standard singleton destruction,
	 * while the context's BeanFactory is still active.
	 * @see #getBeanFactory()
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#destroySingletons()
	 */
	protected void destroyBeans() {
		getBeanFactory().destroySingletons();
	}

	/**
	 * Template method which can be overridden to add context-specific shutdown work.
	 * The default implementation is empty.
	 * <p>Called at the end of {@link #doClose}'s shutdown procedure, after
	 * this context's BeanFactory has been closed. If custom shutdown logic
	 * needs to execute while the BeanFactory is still active, override
	 * the {@link #destroyBeans()} method instead.
	 */
	protected void onClose() {
		// For subclasses: do nothing by default.
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	/**
	 * Assert that this context's BeanFactory is currently active,
	 * throwing an {@link IllegalStateException} if it isn't.
	 * <p>Invoked by all {@link BeanFactory} delegation methods that depend
	 * on an active context, i.e. in particular all bean accessor methods.
	 * <p>The default implementation checks the {@link #isActive() 'active'} status
	 * of this context overall. May be overridden for more specific checks, or for a
	 * no-op if {@link #getBeanFactory()} itself throws an exception in such a case.
	 */
	protected void assertBeanFactoryActive() {
		if (!this.active.get()) {
			if (this.closed.get()) {
				throw new IllegalStateException(getDisplayName() + " has been closed already");
			}
			else {
				throw new IllegalStateException(getDisplayName() + " has not been refreshed yet");
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public Object getBean(String name) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, requiredType);
	}

	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, args);
	}

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType);
	}

	@Override
	public <T> T getBean(Class<T> requiredType, Object... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType, args);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public boolean containsBean(String name) {
		return getBeanFactory().containsBean(name);
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isSingleton(name);
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isPrototype(name);
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name);
	}

	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name, allowFactoryBeanInit);
	}

	@Override
	public String[] getAliases(String name) {
		return getBeanFactory().getAliases(name);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public boolean containsBeanDefinition(String beanName) {
		return getBeanFactory().containsBeanDefinition(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return getBeanFactory().getBeanDefinitionCount();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		return getBeanFactory().getBeanDefinitionNames();
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForAnnotation(annotationType);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansWithAnnotation(annotationType);
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	@Nullable
	public BeanFactory getParentBeanFactory() {
		return getParent();
	}

	@Override
	public boolean containsLocalBean(String name) {
		return getBeanFactory().containsLocalBean(name);
	}

	/**
	 * Return the internal bean factory of the parent context if it implements
	 * ConfigurableApplicationContext; else, return the parent context itself.
	 * @see org.springframework.context.ConfigurableApplicationContext#getBeanFactory
	 */
	@Nullable
	protected BeanFactory getInternalParentBeanFactory() {
		return (getParent() instanceof ConfigurableApplicationContext ?
				((ConfigurableApplicationContext) getParent()).getBeanFactory() : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of MessageSource interface
	//---------------------------------------------------------------------

	@Override
	public String getMessage(String code, @Nullable Object[] args, @Nullable String defaultMessage, Locale locale) {
		return getMessageSource().getMessage(code, args, defaultMessage, locale);
	}

	@Override
	public String getMessage(String code, @Nullable Object[] args, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(code, args, locale);
	}

	@Override
	public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(resolvable, locale);
	}

	/**
	 * Return the internal MessageSource used by the context.
	 * @return the internal MessageSource (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	private MessageSource getMessageSource() throws IllegalStateException {
		if (this.messageSource == null) {
			throw new IllegalStateException("MessageSource not initialized - " +
					"call 'refresh' before accessing messages via the context: " + this);
		}
		return this.messageSource;
	}

	/**
	 * Return the internal message source of the parent context if it is an
	 * AbstractApplicationContext too; else, return the parent context itself.
	 */
	@Nullable
	protected MessageSource getInternalParentMessageSource() {
		return (getParent() instanceof AbstractApplicationContext ?
				((AbstractApplicationContext) getParent()).messageSource : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of ResourcePatternResolver interface
	//---------------------------------------------------------------------

	@Override
	public Resource[] getResources(String locationPattern) throws IOException {
		return this.resourcePatternResolver.getResources(locationPattern);
	}


	//---------------------------------------------------------------------
	// Implementation of Lifecycle interface
	//---------------------------------------------------------------------

	@Override
	public void start() {
		getLifecycleProcessor().start();
		publishEvent(new ContextStartedEvent(this));
	}

	@Override
	public void stop() {
		getLifecycleProcessor().stop();
		publishEvent(new ContextStoppedEvent(this));
	}

	@Override
	public boolean isRunning() {
		return (this.lifecycleProcessor != null && this.lifecycleProcessor.isRunning());
	}


	//---------------------------------------------------------------------
	// Abstract methods that must be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Subclasses must implement this method to perform the actual configuration load.
	 * The method is invoked by {@link #refresh()} before any other initialization work.
	 * <p>A subclass will either create a new bean factory and hold a reference to it,
	 * or return a single BeanFactory instance that it holds. In the latter case, it will
	 * usually throw an IllegalStateException if refreshing the context more than once.
	 * @throws BeansException if initialization of the bean factory failed
	 * @throws IllegalStateException if already initialized and multiple refresh
	 * attempts are not supported
	 */
	protected abstract void refreshBeanFactory() throws BeansException, IllegalStateException;

	/**
	 * Subclasses must implement this method to release their internal bean factory.
	 * This method gets invoked by {@link #close()} after all other shutdown work.
	 * <p>Should never throw an exception but rather log shutdown failures.
	 */
	protected abstract void closeBeanFactory();

	/**
	 * Subclasses must return their internal bean factory here. They should implement the
	 * lookup efficiently, so that it can be called repeatedly without a performance penalty.
	 * <p>Note: Subclasses should check whether the context is still active before
	 * returning the internal bean factory. The internal factory should generally be
	 * considered unavailable once the context has been closed.
	 * @return this application context's internal bean factory (never {@code null})
	 * @throws IllegalStateException if the context does not hold an internal bean factory yet
	 * (usually if {@link #refresh()} has never been called) or if the context has been
	 * closed already
	 * @see #refreshBeanFactory()
	 * @see #closeBeanFactory()
	 */
	@Override
	public abstract ConfigurableListableBeanFactory getBeanFactory() throws IllegalStateException;


	/**
	 * Return information about this context.
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(getDisplayName());
		sb.append(", started on ").append(new Date(getStartupDate()));
		ApplicationContext parent = getParent();
		if (parent != null) {
			sb.append(", parent: ").append(parent.getDisplayName());
		}
		return sb.toString();
	}

}
