/*
 * Copyright (C) 2015 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.auto.common.MoreElements.getLocalAndInheritedMethods;
import static com.google.auto.common.MoreTypes.asDeclared;
import static com.google.common.base.Preconditions.checkState;
import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static dagger.internal.codegen.AnnotationSpecs.Suppression.UNCHECKED;
import static dagger.internal.codegen.BindingRequest.bindingRequest;
import static dagger.internal.codegen.CodeBlocks.toParametersCodeBlock;
import static dagger.internal.codegen.ComponentGenerator.componentName;
import static dagger.internal.codegen.ComponentImplementation.MethodSpecKind.BUILDER_METHOD;
import static dagger.internal.codegen.ComponentImplementation.MethodSpecKind.CANCELLATION_LISTENER_METHOD;
import static dagger.internal.codegen.ComponentImplementation.MethodSpecKind.COMPONENT_METHOD;
import static dagger.internal.codegen.ComponentImplementation.MethodSpecKind.CONSTRUCTOR;
import static dagger.internal.codegen.ComponentImplementation.MethodSpecKind.INITIALIZE_METHOD;
import static dagger.internal.codegen.ComponentImplementation.TypeSpecKind.COMPONENT_BUILDER;
import static dagger.internal.codegen.ComponentImplementation.TypeSpecKind.SUBCOMPONENT;
import static dagger.internal.codegen.DaggerStreams.toImmutableList;
import static dagger.producers.CancellationPolicy.Propagation.PROPAGATE;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PROTECTED;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

import com.google.auto.common.MoreTypes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.codegen.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.internal.codegen.ModifiableBindingMethods.ModifiableBindingMethod;
import dagger.model.Key;
import dagger.producers.internal.CancellationListener;
import dagger.producers.internal.Producers;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.type.DeclaredType;

/** Factory for {@link ComponentImplementation}s. */
final class ComponentImplementationFactory {
  private static final String MAY_INTERRUPT_IF_RUNNING = "mayInterruptIfRunning";

  /**
   * How many statements per {@code initialize()} or {@code onProducerFutureCancelled()} method
   * before they get partitioned.
   */
  private static final int STATEMENTS_PER_METHOD = 100;

  private static final String CANCELLATION_LISTENER_METHOD_NAME = "onProducerFutureCancelled";

  private final DaggerTypes types;
  private final DaggerElements elements;
  private final KeyFactory keyFactory;
  private final CompilerOptions compilerOptions;
  private final BindingGraphFactory bindingGraphFactory;

  @Inject
  ComponentImplementationFactory(
      DaggerTypes types,
      DaggerElements elements,
      KeyFactory keyFactory,
      CompilerOptions compilerOptions,
      BindingGraphFactory bindingGraphFactory) {
    this.types = types;
    this.elements = elements;
    this.keyFactory = keyFactory;
    this.compilerOptions = compilerOptions;
    this.bindingGraphFactory = bindingGraphFactory;
  }

  /**
   * Returns a top-level (non-nested) component implementation for a binding graph.
   *
   * @throws IllegalStateException if the binding graph is for a subcomponent and
   *     ahead-of-time-subcomponents mode is not enabled
   */
  ComponentImplementation createComponentImplementation(BindingGraph bindingGraph) {
    ComponentImplementation componentImplementation =
        topLevelImplementation(componentName(bindingGraph.componentTypeElement()), bindingGraph);
    OptionalFactories optionalFactories = new OptionalFactories(componentImplementation);
    Optional<ComponentBuilderImplementation> componentBuilderImplementation =
        ComponentBuilderImplementation.create(
            componentImplementation, bindingGraph, elements, types);
    ComponentRequirementFields componentRequirementFields =
        new ComponentRequirementFields(
            bindingGraph, componentImplementation, componentBuilderImplementation);
    ComponentBindingExpressions bindingExpressions =
        new ComponentBindingExpressions(
            bindingGraph,
            componentImplementation,
            componentRequirementFields,
            optionalFactories,
            types,
            elements,
            compilerOptions);
    if (componentImplementation.isAbstract()) {
      checkState(
          compilerOptions.aheadOfTimeSubcomponents(),
          "Calling 'componentImplementation()' on %s when not generating ahead-of-time "
              + "subcomponents.",
          bindingGraph.componentTypeElement());
      return new SubcomponentImplementationBuilder(
              Optional.empty(), /* parent */
              bindingGraph,
              componentImplementation,
              optionalFactories,
              bindingExpressions,
              componentRequirementFields,
              componentBuilderImplementation)
          .build();
    } else {
      return new RootComponentImplementationBuilder(
              bindingGraph,
              componentImplementation,
              optionalFactories,
              bindingExpressions,
              componentRequirementFields,
              componentBuilderImplementation.get())
          .build();
    }
  }

  /** Creates a root component or top-level abstract subcomponent implementation. */
  ComponentImplementation topLevelImplementation(ClassName name, BindingGraph graph) {
    return new ComponentImplementation(
        graph.componentDescriptor(),
        name,
        NestingKind.TOP_LEVEL,
        Optional.empty(), // superclassImplementation
        new SubcomponentNames(graph, keyFactory),
        PUBLIC,
        graph.componentDescriptor().kind().isTopLevel() ? FINAL : ABSTRACT);
  }

  private abstract class ComponentImplementationBuilder {
    final BindingGraph graph;
    final ComponentBindingExpressions bindingExpressions;
    final ComponentRequirementFields componentRequirementFields;
    final ComponentImplementation componentImplementation;
    final OptionalFactories optionalFactories;
    final Optional<ComponentBuilderImplementation> componentBuilderImplementation;
    boolean done;

    ComponentImplementationBuilder(
        BindingGraph graph,
        ComponentImplementation componentImplementation,
        OptionalFactories optionalFactories,
        ComponentBindingExpressions bindingExpressions,
        ComponentRequirementFields componentRequirementFields,
        Optional<ComponentBuilderImplementation> componentBuilderImplementation) {
      this.graph = graph;
      this.componentImplementation = componentImplementation;
      this.optionalFactories = optionalFactories;
      this.bindingExpressions = bindingExpressions;
      this.componentRequirementFields = componentRequirementFields;
      this.componentBuilderImplementation = componentBuilderImplementation;
    }

    /**
     * Returns a {@link ComponentImplementation} for this component. This is only intended to be
     * called once (and will throw on successive invocations). If the component must be regenerated,
     * use a new instance.
     */
    final ComponentImplementation build() {
      checkState(
          !done,
          "ComponentImplementationBuilder has already built the ComponentImplementation for [%s].",
          componentImplementation.name());
      setSupertype();
      componentBuilderImplementation
          .map(ComponentBuilderImplementation::componentBuilderClass)
          .ifPresent(this::addBuilderClass);

      getLocalAndInheritedMethods(graph.componentTypeElement(), types, elements)
          .forEach(method -> componentImplementation.claimMethodName(method.getSimpleName()));

      addFactoryMethods();
      addInterfaceMethods();
      addChildComponents();
      addConstructor();

      if (graph.componentDescriptor().kind().isProducer()) {
        addCancellationListenerImplementation();
      }

      done = true;
      return componentImplementation;
    }

    /** Set the supertype for this generated class. */
    final void setSupertype() {
      if (componentImplementation.superclassImplementation().isPresent()) {
        componentImplementation.addSuperclass(
            componentImplementation.superclassImplementation().get().name());
      } else {
        componentImplementation.addSupertype(graph.componentTypeElement());
      }
    }

    /**
     * Adds {@code builder} as a nested builder class. Root components and subcomponents will nest
     * this in different classes.
     */
    abstract void addBuilderClass(TypeSpec builder);

    /** Adds component factory methods. */
    abstract void addFactoryMethods();

    void addInterfaceMethods() {
      // Each component method may have been declared by several supertypes. We want to implement
      // only one method for each distinct signature.
      ImmutableListMultimap<MethodSignature, ComponentMethodDescriptor>
          componentMethodsBySignature =
              Multimaps.index(
                  graph.componentDescriptor().entryPointMethods(), this::getMethodSignature);
      for (List<ComponentMethodDescriptor> methodsWithSameSignature :
          Multimaps.asMap(componentMethodsBySignature).values()) {
        ComponentMethodDescriptor anyOneMethod = methodsWithSameSignature.stream().findAny().get();
        MethodSpec methodSpec = bindingExpressions.getComponentMethod(anyOneMethod);

        // If the binding for the component method is modifiable, register it as such.
        ModifiableBindingType modifiableBindingType =
            bindingExpressions
                .modifiableBindingExpressions()
                .registerComponentMethodIfModifiable(anyOneMethod, methodSpec);

        // If the method should be implemented in this component, implement it.
        if (modifiableBindingType.hasBaseClassImplementation()) {
          componentImplementation.addMethod(COMPONENT_METHOD, methodSpec);
        }
      }
    }

    final void addCancellationListenerImplementation() {
      componentImplementation.addSupertype(elements.getTypeElement(CancellationListener.class));
      componentImplementation.claimMethodName(CANCELLATION_LISTENER_METHOD_NAME);

      MethodSpec.Builder methodBuilder =
          methodBuilder(CANCELLATION_LISTENER_METHOD_NAME)
              .addModifiers(PUBLIC)
              .addAnnotation(Override.class)
              .addParameter(boolean.class, MAY_INTERRUPT_IF_RUNNING);
      if (componentImplementation.superclassImplementation().isPresent()) {
        methodBuilder.addStatement(
            "super.$L($L)", CANCELLATION_LISTENER_METHOD_NAME, MAY_INTERRUPT_IF_RUNNING);
      }

      ImmutableList<CodeBlock> cancellationStatements = cancellationStatements();
      if (cancellationStatements.isEmpty()
          && componentImplementation.superclassImplementation().isPresent()) {
        // Partial child implementations that have no new cancellations don't need to override
        // the method just to call super().
        return;
      }

      if (cancellationStatements.size() < STATEMENTS_PER_METHOD) {
        methodBuilder.addCode(CodeBlocks.concat(cancellationStatements)).build();
      } else {
        List<List<CodeBlock>> partitions =
            Lists.partition(cancellationStatements, STATEMENTS_PER_METHOD);
        for (List<CodeBlock> partition : partitions) {
          String methodName = componentImplementation.getUniqueMethodName("cancelProducers");
          MethodSpec method =
              MethodSpec.methodBuilder(methodName)
                  .addModifiers(PRIVATE)
                  .addParameter(boolean.class, MAY_INTERRUPT_IF_RUNNING)
                  .addCode(CodeBlocks.concat(partition))
                  .build();
          methodBuilder.addStatement("$N($L)", method, MAY_INTERRUPT_IF_RUNNING);
          componentImplementation.addMethod(CANCELLATION_LISTENER_METHOD, method);
        }
      }

      addCancelParentStatement(methodBuilder);

      componentImplementation.addMethod(CANCELLATION_LISTENER_METHOD, methodBuilder.build());
    }

    final ImmutableList<CodeBlock> cancellationStatements() {
      // Reversing should order cancellations starting from entry points and going down to leaves
      // rather than the other way around. This shouldn't really matter but seems *slightly*
      // preferable because:
      // When a future that another future depends on is cancelled, that cancellation will propagate
      // up the future graph toward the entry point. Cancelling in reverse order should ensure that
      // everything that depends on a particular node has already been cancelled when that node is
      // cancelled, so there's no need to propagate. Otherwise, when we cancel a leaf node, it might
      // propagate through most of the graph, making most of the cancel calls that follow in the
      // onProducerFutureCancelled method do nothing.
      ImmutableList<Key> cancellationKeys =
          componentImplementation.getCancellableProducerKeys().reverse();

      ImmutableList.Builder<CodeBlock> cancellationStatements = ImmutableList.builder();
      for (Key cancellationKey : cancellationKeys) {
        cancellationStatements.add(
            CodeBlock.of(
                "$T.cancel($L, $N);",
                Producers.class,
                bindingExpressions
                    .getDependencyExpression(
                        bindingRequest(cancellationKey, FrameworkType.PRODUCER_NODE),
                        componentImplementation.name())
                    .codeBlock(),
                MAY_INTERRUPT_IF_RUNNING));
      }
      return cancellationStatements.build();
    }

    void addCancelParentStatement(MethodSpec.Builder methodBuilder) {
      // Does nothing by default. Overridden in subclass(es) to add a statement if and only if the
      // component being generated is a concrete subcomponent implementation with a parent that
      // allows cancellation to propagate to it from subcomponents.
    }

    final MethodSignature getMethodSignature(ComponentMethodDescriptor method) {
      return MethodSignature.forComponentMethod(
          method, MoreTypes.asDeclared(graph.componentTypeElement().asType()), types);
    }

    final void addChildComponents() {
      for (BindingGraph subgraph : graph.subgraphs()) {
        // TODO(b/117833324): Can an abstract inner subcomponent implementation be elided if it's
        // totally empty?
        componentImplementation.addChild(
            subgraph.componentDescriptor(), buildChildImplementation(subgraph));
      }
    }

    final ComponentImplementation getChildSuperclassImplementation(ComponentDescriptor child) {
      // If the current component has a superclass implementation, that superclass
      // should contain a reference to the child.
      if (componentImplementation.superclassImplementation().isPresent()) {
        ComponentImplementation superclassImplementation =
            componentImplementation.superclassImplementation().get();
        Optional<ComponentImplementation> childSuperclassImplementation =
            superclassImplementation.childImplementation(child);
        checkState(
            childSuperclassImplementation.isPresent(),
            "Cannot find abstract implementation of %s within %s while generating implemention "
                + "within %s",
            child.typeElement(),
            superclassImplementation.name(),
            componentImplementation.name());
        return childSuperclassImplementation.get();
      }

      // Otherwise, the enclosing component is top-level, so we must recreate the implementation
      // object for the base implementation of the child by truncating the binding graph at the
      // child.
      BindingGraph truncatedBindingGraph = bindingGraphFactory.create(child);
      return createComponentImplementation(truncatedBindingGraph);
    }

    final ComponentImplementation buildChildImplementation(BindingGraph childGraph) {
      ComponentImplementation childImplementation =
          compilerOptions.aheadOfTimeSubcomponents()
              ? abstractInnerSubcomponent(childGraph.componentDescriptor())
              : concreteSubcomponent(childGraph.componentDescriptor());
      Optional<ComponentBuilderImplementation> childBuilderImplementation =
          ComponentBuilderImplementation.create(childImplementation, childGraph, elements, types);
      ComponentRequirementFields childComponentRequirementFields =
          componentRequirementFields.forChildComponent(
              childGraph, childImplementation, childBuilderImplementation);
      ComponentBindingExpressions childBindingExpressions =
          bindingExpressions.forChildComponent(
              childGraph, childImplementation, childComponentRequirementFields);
      return new SubcomponentImplementationBuilder(
              Optional.of(this),
              childGraph,
              childImplementation,
              optionalFactories,
              childBindingExpressions,
              childComponentRequirementFields,
              childBuilderImplementation)
          .build();
    }

    /** Creates an inner abstract subcomponent implementation. */
    final ComponentImplementation abstractInnerSubcomponent(ComponentDescriptor child) {
      return new ComponentImplementation(
          componentImplementation,
          child,
          Optional.of(getChildSuperclassImplementation(child)),
          PUBLIC,
          componentImplementation.isAbstract() ? ABSTRACT : FINAL);
    }

    /** Creates a concrete inner subcomponent implementation. */
    final ComponentImplementation concreteSubcomponent(ComponentDescriptor child) {
      return new ComponentImplementation(
          componentImplementation,
          child,
          Optional.empty(), // superclassImplementation
          PRIVATE,
          FINAL);
    }

    final void addConstructor() {
      List<List<CodeBlock>> partitions =
          Lists.partition(componentImplementation.getInitializations(), STATEMENTS_PER_METHOD);

      ImmutableList<ParameterSpec> constructorParameters = constructorParameters();
      MethodSpec.Builder constructor =
          constructorBuilder()
              .addModifiers(componentImplementation.isAbstract() ? PROTECTED : PRIVATE)
              .addParameters(constructorParameters);
      componentImplementation.setConstructorParameters(constructorParameters);
      componentImplementation
          .superclassImplementation()
          .ifPresent(
              superclassImplementation ->
                  constructor.addStatement(
                      CodeBlock.of(
                          "super($L)",
                          superclassImplementation.constructorParameters().stream()
                              .map(param -> CodeBlock.of("$N", param))
                              .collect(toParametersCodeBlock()))));

      ImmutableList<ParameterSpec> initializeParameters = initializeParameters();
      CodeBlock initializeParametersCodeBlock =
          constructorParameters.stream()
              .map(param -> CodeBlock.of("$N", param))
              .collect(toParametersCodeBlock());

      for (List<CodeBlock> partition : partitions) {
        String methodName = componentImplementation.getUniqueMethodName("initialize");
        MethodSpec.Builder initializeMethod =
            methodBuilder(methodName)
                .addModifiers(PRIVATE)
                /* TODO(gak): Strictly speaking, we only need the suppression here if we are also
                 * initializing a raw field in this method, but the structure of this code makes it
                 * awkward to pass that bit through.  This will be cleaned up when we no longer
                 * separate fields and initialization as we do now. */
                .addAnnotation(AnnotationSpecs.suppressWarnings(UNCHECKED))
                .addCode(CodeBlocks.concat(partition));
        initializeMethod.addParameters(initializeParameters);
        constructor.addStatement("$L($L)", methodName, initializeParametersCodeBlock);
        componentImplementation.addMethod(INITIALIZE_METHOD, initializeMethod.build());
      }
      componentImplementation.addMethod(CONSTRUCTOR, constructor.build());
    }

    /** Returns the list of {@link ParameterSpec}s for the initialize methods. */
    final ImmutableList<ParameterSpec> initializeParameters() {
      return constructorParameters().stream()
          .map(param -> param.toBuilder().addModifiers(FINAL).build())
          .collect(toImmutableList());
    }

    /** Returns the list of {@link ParameterSpec}s for the constructor. */
    final ImmutableList<ParameterSpec> constructorParameters() {
      if (componentBuilderImplementation.isPresent()) {
        return ImmutableList.of(
            ParameterSpec.builder(componentBuilderImplementation.get().name(), "builder").build());
      } else if (componentImplementation.isAbstract() && componentImplementation.isNested()) {
        // If we're generating an abstract inner subcomponent, then we are not implementing module
        // instance bindings and have no need for factory method parameters.
        return ImmutableList.of();
      } else if (graph.factoryMethod().isPresent()) {
        return getFactoryMethodParameterSpecs(graph);
      } else if (componentImplementation.isAbstract()) {
        // If we're generating an abstract base implementation of a subcomponent it's acceptable to
        // have neither a builder nor factory method.
        return ImmutableList.of();
      } else {
        throw new AssertionError(
            "Expected either a component builder or factory method but found neither.");
      }
    }
  }

  /** Builds a root component implementation. */
  private final class RootComponentImplementationBuilder extends ComponentImplementationBuilder {
    private final ClassName componentBuilderClassName;

    RootComponentImplementationBuilder(
        BindingGraph graph,
        ComponentImplementation componentImplementation,
        OptionalFactories optionalFactories,
        ComponentBindingExpressions bindingExpressions,
        ComponentRequirementFields componentRequirementFields,
        ComponentBuilderImplementation componentBuilderImplementation) {
      super(
          graph,
          componentImplementation,
          optionalFactories,
          bindingExpressions,
          componentRequirementFields,
          Optional.of(componentBuilderImplementation));
      this.componentBuilderClassName = componentBuilderImplementation.name();
    }

    @Override
    void addBuilderClass(TypeSpec builder) {
      componentImplementation.addType(COMPONENT_BUILDER, builder);
    }

    @Override
    void addFactoryMethods() {
      // Only top-level components have the factory builder() method.
      // Mirror the user's builder API type if they had one.
      MethodSpec builderFactoryMethod =
          methodBuilder("builder")
              .addModifiers(PUBLIC, STATIC)
              .returns(
                  builderSpec()
                      .map(builderSpec -> ClassName.get(builderSpec.builderDefinitionType()))
                      .orElse(componentBuilderClassName))
              .addStatement("return new $T()", componentBuilderClassName)
              .build();
      componentImplementation.addMethod(BUILDER_METHOD, builderFactoryMethod);
      if (canInstantiateAllRequirements()) {
        CharSequence buildMethodName =
            builderSpec().isPresent() ? builderSpec().get().buildMethod().getSimpleName() : "build";
        componentImplementation.addMethod(
            BUILDER_METHOD,
            methodBuilder("create")
                .returns(ClassName.get(super.graph.componentTypeElement()))
                .addModifiers(PUBLIC, STATIC)
                .addStatement("return new Builder().$L()", buildMethodName)
                .build());
      }
    }

    Optional<ComponentDescriptor.BuilderSpec> builderSpec() {
      return graph.componentDescriptor().builderSpec();
    }

    /** {@code true} if all of the graph's required dependencies can be automatically constructed */
    boolean canInstantiateAllRequirements() {
      return !Iterables.any(
          graph.componentRequirements(),
          dependency -> dependency.requiresAPassedInstance(elements, types));
    }
  }

  /**
   * Builds a subcomponent implementation. If generating ahead-of-time subcomponents, this may be an
   * abstract base class implementation, an abstract inner implementation, or a concrete
   * implementation that extends an abstract base implementation. Otherwise it represents a private,
   * inner, concrete, final implementation of a subcomponent which extends a user defined type.
   */
  private final class SubcomponentImplementationBuilder extends ComponentImplementationBuilder {
    final Optional<ComponentImplementationBuilder> parent;

    SubcomponentImplementationBuilder(
        Optional<ComponentImplementationBuilder> parent,
        BindingGraph graph,
        ComponentImplementation componentImplementation,
        OptionalFactories optionalFactories,
        ComponentBindingExpressions bindingExpressions,
        ComponentRequirementFields componentRequirementFields,
        Optional<ComponentBuilderImplementation> componentBuilderImplementation) {
      super(
          graph,
          componentImplementation,
          optionalFactories,
          bindingExpressions,
          componentRequirementFields,
          componentBuilderImplementation);
      this.parent = parent;
    }

    @Override
    void addBuilderClass(TypeSpec builder) {
      if (parent.isPresent()) {
        // In an inner implementation of a subcomponent the builder is a peer class.
        parent.get().componentImplementation.addType(SUBCOMPONENT, builder);
      } else {
        componentImplementation.addType(SUBCOMPONENT, builder);
      }
    }

    @Override
    void addFactoryMethods() {
      // Only construct instances of subcomponents that have concrete implementations.
      if (!componentImplementation.isAbstract()) {
        // Use the parent's factory method to create this subcomponent if the
        // subcomponent was not added via {@link dagger.Module#subcomponents()}.
        graph.factoryMethod().ifPresent(this::createSubcomponentFactoryMethod);
      }
    }

    void createSubcomponentFactoryMethod(ExecutableElement factoryMethod) {
      checkState(parent.isPresent());
      parent
          .get()
          .componentImplementation
          .addMethod(
              COMPONENT_METHOD,
              MethodSpec.overriding(factoryMethod, parentType(), types)
                  .addStatement(
                      "return new $T($L)",
                      componentImplementation.name(),
                      getFactoryMethodParameterSpecs(graph).stream()
                          .map(param -> CodeBlock.of("$N", param))
                          .collect(toParametersCodeBlock()))
                  .build());
    }

    DeclaredType parentType() {
      return asDeclared(parent.get().graph.componentTypeElement().asType());
    }

    @Override
    void addInterfaceMethods() {
      if (componentImplementation.superclassImplementation().isPresent()) {
        // Since we're overriding a subcomponent implementation we add to its implementation given
        // an expanded binding graph.

        // Override modifiable binding methods.
        for (ModifiableBindingMethod modifiableBindingMethod :
            componentImplementation.getModifiableBindingMethods()) {
          bindingExpressions
              .modifiableBindingExpressions()
              .getModifiableBindingMethod(modifiableBindingMethod)
              .ifPresent(
                  method -> componentImplementation.addImplementedModifiableBindingMethod(method));
        }
      } else {
        super.addInterfaceMethods();
      }
    }

    @Override
    void addCancelParentStatement(MethodSpec.Builder methodBuilder) {
      if (shouldPropagateCancellationToParent()) {
        methodBuilder.addStatement(
            "$T.this.$L($L)",
            parent.get().componentImplementation.name(),
            CANCELLATION_LISTENER_METHOD_NAME,
            MAY_INTERRUPT_IF_RUNNING);
      }
    }

    boolean shouldPropagateCancellationToParent() {
      return parent.isPresent()
          && parent
              .get()
              .componentImplementation
              .componentDescriptor()
              .cancellationPolicy()
              .map(policy -> policy.fromSubcomponents().equals(PROPAGATE))
              .orElse(false);
    }
  }

  /** Returns the list of {@link ParameterSpec}s for the corresponding graph's factory method. */
  private static ImmutableList<ParameterSpec> getFactoryMethodParameterSpecs(BindingGraph graph) {
    return graph
        .factoryMethodParameters()
        .values()
        .stream()
        .map(ParameterSpec::get)
        .collect(toImmutableList());
  }
}
