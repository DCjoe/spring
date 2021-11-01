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
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.*;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	/**
	 * 调用spring默认的后置处理器，最最重要的是ConfigurationClassPostProcessor，这个类扫描
	 * 我们配置的类
	 * @param beanFactory  bean工厂，子类是DefaultListableBeanFactory
	 * @param beanFactoryPostProcessors 用户手动注册的BeanFactory后置处理器
	 * 在这之前，我们的register和构造都没有注册过后置处理器，而我们的Import也还没被扫描
	 * spring目前还没开始扫描，在这之前仅仅做了工厂初始化和默认的spring内置处理器，以及将我们的配置类注册
	 * 到工厂的bd map中 ，所以这里传入的beanFactoryPostProcessors如果在启动的时候没有添加后置处理器，那么这里传入的为空
	 */
	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22

		/**
		 * 下面的bean工厂后置处理器其实就是围绕BeanDefinitionRegistryPostProcessor和BeanFactoryPostProcessor类进行的
		 * 其中BeanDefinitionRegistryPostProcessor是BeanFactoryPostProcessor的子类,子类中提供了一个方法postProcessBeanDefinitionRegistry
		 * 是可以对BeanDefinition进行再次注册的，而BeanFactoryPostProcessor中提供的方法是不能进行注册BeanDefinition，只能对bean工厂就行
		 * 处理的，所以实现了BeanDefinitionRegistryPostProcessor接口的bean具有两个功能，就是注册BeanDefinition和操作BeanFactory,
		 * 这个设计人员的思路是将所有的bean的后置处理器中的注册BeanDefinition的方法全部执行了再次执行BeanFactoryPostProcessor，所以
		 * 对下面的代码的执行顺序是执行所有实现了BeanDefinitionRegistryPostProcessor中的postProcessBeanDefinitionRegistry
		 * 再执行父类的postProcessBeanFactory
		 * 这里执行的逻辑如下：
		 * 1.循环执行用户手动添加的后置处理器列表，如果后置处理器实现了BeanDefinitionRegistryPostProcessor接口，那么执行
		 * 接口中的postProcessBeanDefinitionRegistry方法；
		 * 2.对容器中实现了BeanDefinitionRegistryPostProcessor接口并且实现了优先级的后PriorityOrdered置处理器执行接口中的postProcessBeanDefinitionRegistry方法；
		 * 3.对容器中实现了BeanDefinitionRegistryPostProcessor接口并且实现了优先级的后Ordered置处理器执行接口中的postProcessBeanDefinitionRegistry方法；
		 * 4.对容器中没有实现排序接口的后置处理器拿出来循环处理，每次循环获取一次容器中的实现了BeanDefinitionRegistryPostProcessor接口
		 * 的后置处理器拿出来执行接口中的postProcessBeanDefinitionRegistry方法；然后设置循环为继续，直到取出所有的后置处理器执行。
		 * 5.执行完所有后置处理器中postProcessBeanDefinitionRegistry，然后将所有执行过postProcessBeanDefinitionRegistry的后置处理器
		 * 执行里面的每个后置处理器的postProcessBeanFactory；
		 * 6.取出系统中所有实现了父类BeanFactoryPostProcessor接口的后置处理器拿出来；
		 * 7.过滤掉上面已经执行过的postProcessBeanFactory的后置处理器，然后进行分组；
		 * 8.实现了PriorityOrdered的分一组，实现了Ordered分一组，没有实现排序的分一组；
		 * 9.分别执行每个分组中的工厂方法postProcessBeanFactory，然后结束。
		 */

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		//这里定义个Set存放处理器的Bean名称（为啥用Set，因为Set自动具有自动去重功能），这里的这个processedBeans存放的是已经执行过的后置处理器
		Set<String> processedBeans = new HashSet<>();

		//DefaultListableBeanFactory 肯定继承BeanDefinitionRegistry，所以这个条件肯定成立
		if (beanFactory instanceof BeanDefinitionRegistry) {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			//regularPostProcessors是用来存放应用程序手动添加的自定义的BeanFactoryPostProcessor的子类（Bean工厂的后置处理器）
			//也就是说regularPostProcessors存放了实现了BeanFactoryPostProcessor接口的列表
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			//registryProcessors是用来存放应用程序手动添加的自定义的Bean注册工厂的后置处理器
			//也就是registryProcessors存放的是仅仅实现了BeanDefinitionRegistryPostProcessor类的后置处理器列表
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			/**
			 * 下面这个for循环是处理手动添加的后置处理器，如果实现了BeanDefinitionRegistryPostProcessor，那么先执行
			 * postProcessBeanDefinitionRegistry，然后把这个后置处理器添加到registryProcessors，
			 * 如果没有实现BeanDefinitionRegistryPostProcessor，那么肯定是实现了BeanFactoryPostProcessor
			 * 添加到regularPostProcessors
			 */
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					registryProcessors.add(registryProcessor);
				}
				else {
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			/**
			 * 下面这个变量什么意思呢？这个集合的类型是BeanDefinitionRegistryPostProcessor，表示每次都存放符合条件的BeanDefinitionRegistryPostProcessor
			 * 的beanFactory的后置处理器，然后放入过后，循环去执行里面的后置处理器的postProcessBeanDefinitionRegistry
			 * 执行完成过后清空，然后下一个符合条件的后置处理器又添加进去，执行，就是一个临时变量。
			 */
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			/**
			 * 上面处理的是程序员手动添加的后置处理器，这里是要获取从beanDefinitionMap中注册的所有bean工厂的后置处理器
			 * 但是代码执行到这里，我们的业务定义的bean还没有开始扫描的，这里最多获取的是spring在启动开始添加到beanDefinitionMap
			 * 中的后置处理器，所以这里的思路是：
			 * 获取spring在启动添加到beanDefinitionMap中的bean工厂后置处理器，我们从前面的代码中可以知道spring只有在构建配置类读取
			 * 对象的时候放了一个ConfigurationClassPostProcessor后置处理器，这个后置处理器是spring的核心，非常重要，是对我们的
			 * 配置类进行解析，将配置类中符合条件的class生成BeanDefinition，然后放入到beanDefinitionMap
			 *这里spring的官方的解释是first；实现逻辑是：
			 * 1.将容器中所有实现了BeanDefinitionRegistryPostProcessor的beanName全部拿出来；
			 * 2.将这些后置处理器实现了PriorityOrdered（优先级最高）的分为一组，然后getBean（如果没有，会创建）
			 * 3.然后对这些实现了PriorityOrdered的后置处理器根据Order的数字进行排序，然后执行后置处理器中postProcessBeanDefinitionRegistry方法
			 * 4.执行过的postProcessBeanDefinitionRegistry方法的后置处理器添加到执行过后的集合中processedBeans；
			 * 5.currentRegistryProcessors表示当前执行的后置处理器（实现了BeanDefinitionRegistryPostProcessor接口的bean）
			 * 每次循环将符合条件的后置处理器放入这个集合中，然后去执行，执行完成过后清除。
			 *
			 */
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					//将处理过的后置处理器名称放入processedBeans
					processedBeans.add(ppName);
				}
			}
			/**
			 *  这个方法是排序的，就是上面找到的实现了BeanDefinitionRegistryPostProcessor的工厂后置处理器并且实现了PriorityOrdered
			 *  优先级接口的后置处理器放入了currentRegistryProcessors，如果说这个集合中有4条数据，那么需要对这4条根据order的数字来排序
			 *  判断哪个先执行，所以这里就是一个排序，不重要，只需要知道为什么要排序就行了。
			 */
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			/**
			 * 将上面实现了PriorityOrdered接口的后置处理器全部放入到registryProcessors，这个集合表示实现了BeanDefinitionRegistryPostProcessor
			 * 的所有后置处理器集合，也就是说执行过BeanDefinitionRegistryPostProcessor这个接口里面的核心方法postProcessBeanDefinitionRegistry
			 * 的所有后置处理器都放在这个集合里面
			 */
			registryProcessors.addAll(currentRegistryProcessors);
			/**
			 * 调用postProcessBeanDefinitionRegistry方法，除开程序员手动添加的后置处理器，这里有一个后置处理器就是spring在启动添加的
			 * ConfigurationClassPostProcessor这个后置处理器，这个是解析我们配置类的，主要将我们配置类中配置的扫描路径下的所有
			 * 符合条件的class扫描成BeanDefinition，然后放入beanDefinitionMap中
			 * 所以我们的定义的扫描路径就是在下面这行代码执行的，也就是下面这行代码执行的后置处理器ConfigurationClassPostProcessor
			 * 的postProcessBeanDefinitionRegistry执行的
			 */
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			//执行完成过后，清空这个列表，下面的可以继续用，因为这里面的后置处理器都全部执行过了
			currentRegistryProcessors.clear();

			/**
			 * 下面的和上面的代码一样的，唯一不一样的地方是上面的处理PriorityOrder的，下面的for是处理Ordered的
			 * PriorityOrder的优先级最高，Ordered其次，spring官方的解释是next，也就是上面的是first，这里是next
			 * 也就说这里都在处理BeanDefinitionRegistryPostProcessor，只是分了优先级，根据优先级来执行
			 */

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			/**
			 * spring的官方解释是finally，表示最后的一步，我们看下面的代码是用的循环来获取的；首先要明白上面的后置处理器中
			 * 都只执行了PriorityOrder 和Ordered以及程序员手动添加的后置处理器；你想哈，前三步执行的后置处理器，都有可能添加BeanDefinition
			 * 其中第二步是去解析我们的配置类的，肯定会将系统中程序员定义的所有的bean都扫描成了BeanDefinition，这里的代码逻辑就是说
			 * 获取系统中的所有的后置处理器（实现了BeanDefinitionRegistryPostProcessor），不分优先级，下面的也是执行
			 * BeanDefinitionRegistryPostProcessor中的postProcessBeanDefinitionRegistry方法；下面的代码逻辑是：
			 * 1.循环根据BeanDefinitionRegistryPostProcessor获取后置处理器名字列表；
			 * 2.如果这个后置处理器已经执行过了，那么久不执行；
			 * 3.如果没有执行过，添加到currentRegistryProcessors列表中；
			 * 4.然后设置reiterate为true，表示我本次处理了后置处理器，容器中可能还有后置处理器，循环继续；
			 * 5.然后对找到的符合条件的后置处理器开始执行；
			 * 6.逻辑一样，处理完成过后添加到registryProcessors；
			 * 7.然后清空已经执行过的后置处理器列表；
			 * 下面我们来分析为什么这里要用循环，前面已经说了实现了BeanDefinitionRegistryPostProcessor的后置处理器中的
			 * postProcessBeanDefinitionRegistry方法是可以注册BeanDefinition的，而可以注册BeanDefinition，也就意味着可以注册
			 * BeanDefinitionRegistryPostProcessor类型的BeanDefinition，如果不用循环，不每次循环都去从新获取是否有BeanDefinitionRegistryPostProcessor
			 * 类型的后置处理器，那么你在某一次的 postProcessBeanDefinitionRegistry方法中注册了新的BeanDefinitionRegistryPostProcessor类型
			 * 的后置处理器，那系统不就漏掉了，漏处理了，所以这里用的是while循环，直到你每次获取到的后置处理器列表是空的，那么就表示系统中的所有
			 * bean工厂的BeanDefinitionRegistryPostProcessor类型的后置处理器都已经处理完成了，就可以退出循环了
			 */
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					if (!processedBeans.contains(ppName)) {
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						reiterate = true;
					}
				}
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				registryProcessors.addAll(currentRegistryProcessors);
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
				currentRegistryProcessors.clear();
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			/**
			 * 下面执行的是什么呢？首先来看下registryProcessors这个集合存放的是什么，这个集合存放的是系统中找到的所有
			 * 实现了BeanDefinitionRegistryPostProcessor接口的后置处理器，而regularPostProcessors是实现了BeanFactoryPostProcessor
			 * 的后置处理器列表，实现了BeanDefinitionRegistryPostProcessor必定实现了BeanFactoryPostProcessor
			 * 而regularPostProcessors存放的是程序员手动添加的后置处理器；所以registryProcessors和regularPostProcessors
			 * 包括了系统中的所有的beanFactory后置处理器，而上面的代码逻辑是已经将系统中的所有后置处理器中实现了BeanDefinitionRegistryPostProcessor
			 * 中的postProcessBeanDefinitionRegistry都已经执行完成了，但是父类中的postProcessBeanFactory还没执行呢；
			 * 所以下面的代码逻辑就是把系统中的所有后置处理器的postProcessBeanFactory方法全部执行
			 *
			 */
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			/**
			 * spring的BeanFactoryPostProcessor的设计思想就是提供Bean的注册和工厂的的操作
			 * 这个else意思就是说当前的bean工厂没有实现BeanDefinitionRegistry，那么就没有BeanDefinitionRegistry的
			 * 一说了，就只能调用bean工厂的相关方法了，也就是只能调用postProcessBeanFactory，不能调用postProcessBeanDefinitionRegistry
			 * 简单来说就是只实现了BeanFactoryPostProcessor，没有实现BeanDefinitionRegistryPostProcessor
			 */
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		/**
		 * 下面的代码处理的是就是父类接口BeanFactoryPostProcessor接口中的postProcessBeanFactory方法了
		 * 但是上面都已经执行过了，但是上面执行的是找到了实现了子类接口BeanDefinitionRegistryPostProcessor中的
		 * 的postProcessBeanDefinitionRegistry和父类中postProcessBeanFactory方，但是只是找到了子类的类型的列表去执行的
		 * 但是容器中肯定有只实现了BeanFactoryPostProcessor的后置处理器，只实现了父类的后置处理器也要拿出来执行它的后置处理方法
		 * postProcessBeanFactory，但是拿出来的实现了父类的后置处理器也有可能把实现了子类的后置处理器也拿出来了，因为实现了子类也就实现了父类
		 * 所以下面的逻辑需要把实现了子类的后置处理器给排除掉，也就是上面处理过的后置处理器都存放在了processedBeans中，所以下面的代码逻辑是：
		 * 1.根据父类BeanFactoryPostProcessor得到容器中的所有后置处理器；
		 * 2.过滤掉已经执行过的后置处理器；
		 * 3.将剩下的后置处理器进行分类，实现了PriorityOrdered为一组，实现了Ordered一组，没有实现排序接口的为一组；
		 * 4.根据每一组进行sort排序执行他们的工厂方法postProcessBeanFactory
		 *
		 */
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		beanFactory.clearMetadataCache();
	}

	/**
	 * 从已经扫描成功的beanDefinitionMap中取出bean的后置处理器，也就是说
	 * 在beanDefinitionMap中实现了BeanPostProcessor的BeanDefinition取出来，然后加入到了bean的后置处理器列表中
	 * beanPostProcessors中，当然其中还有分类
	 * @param beanFactory
	 * @param applicationContext
	 */
	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22
		//这里按照BeanPostProcessor的类型从beanDefinitionMap中取出所有的后置处理器名称
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		//计算目前容器中的bean后置处理器的个数
		//beanFactory.getBeanPostProcessorCount() 是系统中现在的后置处理器个数
		//postProcessorNames.length取出的刚刚从beanDefinitionMap中找到的后置处理器个数
		//+1是加的下面的添加的又一个后置处理器器
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		/**
		 * 下面就是对后置处理器进行分类，在循环的时候，这里就开始将得到的后置处理器创建出了对象，然后放入到单例池中
		 * 下面的分类是从实现了PriorityOrdered、Ordered和没有实现的后置处理器进行分类
		 * 1.首先将实现了PriorityOrdered的分一组 priorityOrderedPostProcessors
		 * 2.实现了Ordered的分一组     orderedPostProcessorNames
		 * 3.没有实现上述的两个接口的分一组   nonOrderedPostProcessorNames
		 * 4.如果实现了MergedBeanDefinitionPostProcessor 分一组 internalPostProcessors
		 *
		 */
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		//对实现了PriorityOrdered的后置处理器列表进行排序，排序的类就是启动的时候设置进去的一个排序比较器dependencyComparator
		//就是里面有个属性order，每个后置处理器的order越小，越靠前
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		//然后注册到后置处理器中
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		//下面是对实现了Ordered的进行处理，这里的循环和上面的循环都做了一件相同的事情，就是把实现了MergedBeanDefinitionPostProcessor
		//的后置处理器都单独拿出来加入到了internalPostProcessors，我们知道spring的依赖注入，生命周期的回调用法的后置处理器都实现了
		//MergedBeanDefinitionPostProcessor,所以这里应该是叫做内部的后置处理器单独拿出来
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		//下面的是没有实现了上面的排序相关的后置处理器拿出来循环，如果实现了MergedBeanDefinitionPostProcessor都放在internalPostProcessors中
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		//将没有实现排序相关的后置处理器也注册到缓存中
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		/**
		 * 这里将内部实现了MergedBeanDefinitionPostProcessor的后置处理器进行排序，排序也是通过order来的
		 * 如果没有order，那么就是默认的顺序
		 * 然后又加入到后置处理器列表中，我怎么感觉这里添加和上面的重复了
		 * 所以这里要理解spring的设计思路，就是说上面虽然添加了internalPostProcessors中的一个或者几个后置处理器
		 *
		 * 而这里单独那戳来又添加一次，也就是说最后的顺序就是其他后置处理器，包括程序员自己定义的排在前面，然后最后
		 * 再添加MergedBeanDefinitionPostProcessor类型的后置处理器，如果前面你添加了，我这里添加的时候先移除
		 * 然后默认添加到末尾，为什么要怎么做呢？我之前研究了spring的生命周期，spring的依赖注入和生命周期的回调都是通过
		 * MergedBeanDefinitionPostProcessor来实现的，也就是说依赖注入完成过后，这个bean差不多就只有初始化方法的调用了
		 * 那么放在最后调用的目的也就是说你前面的后置处理器先把该做的事情做完，等你们都做完该做的事情了，那么MergedBeanDefinitionPostProcessor
		 * 要开始做事情了，而它要做的是就是依赖注入和生命周期回调的一些处理，所以spring的设计应该是这样想的，这个只是我的个人理解
		 *
		 */
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		/**
		 * 这里再添加一个后置处理器ApplicationListenerDetector，这个后置处理器是不是很熟悉，好像在哪儿见过
		 * 的确，这个后置处理器是在refresh中的prepareBeanFactory初始化工厂的时候添加了一次，那这里为什么又要添加一次
		 * spring大概是这样设计的，这个后置处理器是获取系统中所有的实现了ApplicationLister的BeanDefinition添加到
		 * 事件监听器中，前面准备工厂的时候添加了，但是到这里spring已经经过了扫描我们定义的类过程了，那么这个时候所有的类
		 * 都在BeanDefinition中了，这个时候再添加就是后面调用的时候可以获取到更全的时间监听器类
		 * 也就是前面添加的时候，可能系统中的事件监听器还真是系统中默认的，而这里添加的就是表示包括而来系统默认的和用户添加的自定义的
		 * ，所以比较全，反正我这样理解的，而且你仔细看下ApplicationListenerDetector这个类，它重写了equals方法
		 * 也就是你每次都是new出来的对象，但是其实equals判断是一个对象，在remove的时候，虽然每次都是new的，但是其实
		 * 就只有一个，也就是bean的后置处理器中只会有一个这么后置处理器ApplicationListenerDetector
		 *
		 */
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 * * 这里是调用BeanFactory工厂的后置处理器，包括系统已经添加进去的和程序员自己定义的bean工厂后置处理器
	 *  * 这个后置处理器是BeanDefinition注册的后置处理器
	 *  * 在spring中，这个方法里面有个比较重要的后置处理器ConfigurationClassPostProcessor就是在这里调用的
	 *  * 这个后置处理器主要处理我们的配置类，比如启动注册的配置类Appconfig，那么这里会获取这个配置类中的
	 *  * 配置的扫描类路信息，包括加了@Component、@ComponentScan、@Import @ImportResource等注解都会被认为是配置类
	 *  * 然后进行扫描注册成BeanDefinition
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry, ApplicationStartup applicationStartup) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanDefRegistry = applicationStartup.start("spring.context.beandef-registry.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanDefinitionRegistry(registry);
			postProcessBeanDefRegistry.end();
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanFactory = beanFactory.getApplicationStartup().start("spring.context.bean-factory.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanFactory(beanFactory);
			postProcessBeanFactory.end();
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		if (beanFactory instanceof AbstractBeanFactory) {
			// Bulk addition is more efficient against our CopyOnWriteArrayList there
			((AbstractBeanFactory) beanFactory).addBeanPostProcessors(postProcessors);
		}
		else {
			for (BeanPostProcessor postProcessor : postProcessors) {
				beanFactory.addBeanPostProcessor(postProcessor);
			}
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
