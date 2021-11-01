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
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for identifying {@link Configuration} classes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 */
abstract class ConfigurationClassUtils {

	public static final String CONFIGURATION_CLASS_FULL = "full";

	public static final String CONFIGURATION_CLASS_LITE = "lite";

	public static final String CONFIGURATION_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "configurationClass");

	private static final String ORDER_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "order");


	private static final Log logger = LogFactory.getLog(ConfigurationClassUtils.class);

	private static final Set<String> candidateIndicators = new HashSet<>(8);

	static {
		candidateIndicators.add(Component.class.getName());
		candidateIndicators.add(ComponentScan.class.getName());
		candidateIndicators.add(Import.class.getName());
		candidateIndicators.add(ImportResource.class.getName());
	}


	/**
	 * Check whether the given bean definition is a candidate for a configuration class
	 * (or a nested component class declared within a configuration/component class,
	 * to be auto-registered as well), and mark it accordingly.
	 * @param beanDef the bean definition to check
	 * @param metadataReaderFactory the current factory in use by the caller
	 * @return whether the candidate qualifies as (any kind of) configuration class
	 */
	public static boolean checkConfigurationClassCandidate(
			BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {

		//判断我们的beanClass是否为空或者是否是工程方法名字，显然这里不是
		String className = beanDef.getBeanClassName();
		if (className == null || beanDef.getFactoryMethodName() != null) {
			return false;
		}

		AnnotationMetadata metadata;
		//我们的配置类Appconfig肯定是AnnotatedBeanDefinition，所以这里的条件肯定能进去
		/**
		 * 我们的配置类注册的时候，是通过ApplicationContext.register注册进去的，注册的时候Appconfig是通过asm技术将这个类上的所有
		 * 注解信息处理成一个元数据，所以Appconfig肯定是一个注解的bd，你可以回头去看下register方法里面的处理逻辑就知道了
		 * Appconfig就是一个注解的bd，也就是AnnotatedBeanDefinition
		 * 下面的两个判断的意思就是说如果你是一个注解的bd（AnnotatedBeanDefinition）， 那么之前在创建这个bd的时候就已经拿到了它的所有注解
		 * 信息，并且生成了元数据信息，如果你是一个AbstractBeanDefinition，那么这里重新调用asm的方法去生成类的元数据信息
		 * 还有一个判断就是如果说你的这个bd是BeanFactoryPostProcessor、BeanPostProcessor等类型，那么直接返回，就表示不是一个配置类的候选者
		 * 这个肯定是的，如果是这几种类型，表示是spring内置的一些类，肯定不是我们要扫描的配置类候选者
		 */
		if (beanDef instanceof AnnotatedBeanDefinition &&
				className.equals(((AnnotatedBeanDefinition) beanDef).getMetadata().getClassName())) {
			// Can reuse the pre-parsed metadata from the given BeanDefinition...
			metadata = ((AnnotatedBeanDefinition) beanDef).getMetadata();
		}
		else if (beanDef instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) beanDef).hasBeanClass()) {
			// Check already loaded Class if present...
			// since we possibly can't even load the class file for this Class.
			Class<?> beanClass = ((AbstractBeanDefinition) beanDef).getBeanClass();
			//判断如果BeanDefinition是下面这几种类型，那么就表示不是配置类的候选者，是spring的内置的一些bean而已，就给它过滤掉
			if (BeanFactoryPostProcessor.class.isAssignableFrom(beanClass) ||
					BeanPostProcessor.class.isAssignableFrom(beanClass) ||
					AopInfrastructureBean.class.isAssignableFrom(beanClass) ||
					EventListenerFactory.class.isAssignableFrom(beanClass)) {
				return false;
			}
			//如果不是spring内置的一些bd，那么又是一个是抽象的bd，那么这里去获取下这个bd中BeanClass中的所有元数据信息
			metadata = AnnotationMetadata.introspect(beanClass);
		}
		else {
			try {
				//如果不是注解的bd，也不是抽象的bd，那么这里直接去重新拿到到这个beanclass中的所有元数据信息
				MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(className);
				metadata = metadataReader.getAnnotationMetadata();
			}
			catch (IOException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Could not find class file for introspecting configuration annotations: " +
							className, ex);
				}
				return false;
			}
		}

		//代码运行到这里的时候，如果是spring远古的bd（spring内部bean^_^）都在metadata中了
		//所以下面判断非常重要，如果是spring的内部bean（AbstractBeanDefinition或者RootBeanDefinition）
		//那么下面的if和elseif都不会进，直接进入else返回false了
		//***如果我们的配置类AppConfig加了注解@Configuration，这里非常重要，设置一个属性full（后续将这个属性的重要性）
		//如果我们的配置类AppConfig没有加@Configuration，那么config肯定为空，
		// 但是isConfigurationCandidate(metadata)这个方法必须要返回true才是我们的想要得到的结果，也就是这个方法是来
		//判断你这个配置类是否加了
		//    candidateIndicators.add(Component.class.getName());
		//    candidateIndicators.add(ComponentScan.class.getName());
		//    candidateIndicators.add(Import.class.getName());
		//    candidateIndicators.add(ImportResource.class.getName());
		//所以这个方法就是看你有没有加这些注解，如果加了，则设置一个属性lite到配置类的bd中

		/**
		 *这里面的逻辑就简单描述下：
		 * 1.你的配置类是否加了@Configuration注解，如果加了，并且@Configuration中的proxyBeanMethods是true的话，那么加一个full，表示
		 * 全注解，需要生成配置类的代理对象（cglib）,如果你加了@Configuration，但是你的代理方法proxyBeanMethods是false的话，那么也不是一个全注解
		 * 也不加full属性，所以配置类的代理对象是根据是是否加了@Configuraiton，如果加了，是否重新定义了proxyBeanMethods这个属性.
		 *
		 * 2.如果没有加@Configuration注解，判断你是否有@Component、@ComponScan、@Import、@ImportResouce注解
		 * 如果加了这些注解的一个或者多个，都认为是一个配置类，但是不是全注解，bd中设置一个参数lite
		 *
		 */
		Map<String, Object> config = metadata.getAnnotationAttributes(Configuration.class.getName());
		if (config != null && !Boolean.FALSE.equals(config.get("proxyBeanMethods"))) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
		}
		else if (config != null || isConfigurationCandidate(metadata)) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
		}
		else {
			return false;
		}

		// It's a full or lite configuration candidate... Let's determine the order value, if any.
		//当有多个配置类的时候，可以加@Order来设置执行的先后顺序，说白了就是排序，看谁先执行
		Integer order = getOrder(metadata);
		if (order != null) {
			//如果加了@order，则设置一个属性到bd中，后续执行bd扫描的时候就可以根据order的属性来设置谁先启动
			beanDef.setAttribute(ORDER_ATTRIBUTE, order);
		}

		return true;
	}

	/**
	 * Check the given metadata for a configuration class candidate
	 * (or nested component class declared within a configuration/component class).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be registered for
	 * configuration class processing; {@code false} otherwise
	 */
	public static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
		// Do not consider an interface or an annotation...
		if (metadata.isInterface()) {
			return false;
		}

		// Any of the typical annotations found?
		for (String indicator : candidateIndicators) {
			if (metadata.isAnnotated(indicator)) {
				return true;
			}
		}

		// Finally, let's look for @Bean methods...
		return hasBeanMethods(metadata);
	}

	static boolean hasBeanMethods(AnnotationMetadata metadata) {
		try {
			return metadata.hasAnnotatedMethods(Bean.class.getName());
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to introspect @Bean methods on class [" + metadata.getClassName() + "]: " + ex);
			}
			return false;
		}
	}

	/**
	 * Determine the order for the given configuration class metadata.
	 * @param metadata the metadata of the annotated class
	 * @return the {@code @Order} annotation value on the configuration class,
	 * or {@code Ordered.LOWEST_PRECEDENCE} if none declared
	 * @since 5.0
	 */
	@Nullable
	public static Integer getOrder(AnnotationMetadata metadata) {
		Map<String, Object> orderAttributes = metadata.getAnnotationAttributes(Order.class.getName());
		return (orderAttributes != null ? ((Integer) orderAttributes.get(AnnotationUtils.VALUE)) : null);
	}

	/**
	 * Determine the order for the given configuration class bean definition,
	 * as set by {@link #checkConfigurationClassCandidate}.
	 * @param beanDef the bean definition to check
	 * @return the {@link Order @Order} annotation value on the configuration class,
	 * or {@link Ordered#LOWEST_PRECEDENCE} if none declared
	 * @since 4.2
	 */
	public static int getOrder(BeanDefinition beanDef) {
		Integer order = (Integer) beanDef.getAttribute(ORDER_ATTRIBUTE);
		return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
	}

}
