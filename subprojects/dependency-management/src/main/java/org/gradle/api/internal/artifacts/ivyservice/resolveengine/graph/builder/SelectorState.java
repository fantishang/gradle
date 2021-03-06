/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.artifacts.ResolvedVersionConstraint;
import org.gradle.api.internal.artifacts.dependencies.DefaultResolvedVersionConstraint;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphSelector;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.selectors.ResolvableSelectorState;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionDescriptorInternal;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionReasonInternal;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.VersionSelectionReasons;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.component.model.LocalOriginDependencyMetadata;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver;
import org.gradle.internal.resolve.result.BuildableComponentIdResolveResult;
import org.gradle.internal.resolve.result.ComponentIdResolveResult;
import org.gradle.internal.resolve.result.DefaultBuildableComponentIdResolveResult;

import static org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.VersionSelectionReasons.CONSTRAINT;
import static org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.VersionSelectionReasons.REQUESTED;

/**
 * Resolution state for a given module version selector.
 */
class SelectorState implements DependencyGraphSelector, ResolvableSelectorState {
    // TODO:DAZ Should inject this
    private static final VersionSelectorScheme VERSION_SELECTOR_SCHEME = new DefaultVersionSelectorScheme(new DefaultVersionComparator());
    private final Long id;
    private final DependencyState dependencyState;
    private final DependencyMetadata dependencyMetadata;
    private final DependencyToComponentIdResolver resolver;
    private final ResolvedVersionConstraint versionConstraint;

    private ComponentIdResolveResult idResolveResult;
    private ModuleVersionResolveException failure;
    private ModuleResolveState targetModule;
    ComponentState selected;

    SelectorState(Long id, DependencyState dependencyState, DependencyToComponentIdResolver resolver, ResolveState resolveState, ModuleIdentifier targetModuleId) {
        this.id = id;
        this.dependencyState = dependencyState;
        this.dependencyMetadata = dependencyState.getDependency();
        this.resolver = resolver;
        this.targetModule = resolveState.getModule(targetModuleId);
        this.versionConstraint = resolveVersionConstraint(dependencyMetadata.getSelector());
        targetModule.addSelector(this);
    }

    private ResolvedVersionConstraint resolveVersionConstraint(ComponentSelector selector) {
        if (selector instanceof ModuleComponentSelector) {
            return new DefaultResolvedVersionConstraint(((ModuleComponentSelector) selector).getVersionConstraint(), VERSION_SELECTOR_SCHEME);
        }
        return null;
    }

    @Override
    public Long getResultId() {
        return id;
    }

    @Override
    public String toString() {
        return dependencyMetadata.toString();
    }

    @Override
    public ComponentSelector getRequested() {
        return dependencyState.getRequested();
    }

    public ModuleResolveState getTargetModule() {
        return targetModule;
    }

    /**
     * Return any failure to resolve the component selector to id, or failure to resolve component metadata for id.
     */
    ModuleVersionResolveException getFailure() {
        return failure;
    }

    /**
     * Does the work of actually resolving a component selector to a component identifier.
     */
    public ComponentIdResolveResult resolve() {
        if (idResolveResult != null) {
            return idResolveResult;
        }

        BuildableComponentIdResolveResult idResolveResult = new DefaultBuildableComponentIdResolveResult();
        if (dependencyState.failure != null) {
            idResolveResult.failed(dependencyState.failure);
        } else {
            resolver.resolve(dependencyMetadata, versionConstraint, idResolveResult);
        }

        if (idResolveResult.getFailure() != null) {
            failure = idResolveResult.getFailure();
        }

        this.idResolveResult = idResolveResult;
        return idResolveResult;
    }

    public void select(ComponentState selected) {
        // We should never select a component for a different module, but the JVM software model dependency resolution is doing this.
        // TODO Ditch the JVM Software Model plugins and re-add this assertion
//        assert selected.getModule() == targetModule;

        this.selected = selected;
    }

    /**
     * Overrides the component that is the chosen for this selector.
     * This happens when the `ModuleResolveState` is restarted, during conflict resolution or version range merging.
     */
    public void overrideSelection(ComponentState selected) {
        this.selected = selected;

        // Target module can change, if this is called as the result of a module replacement conflict.
        this.targetModule = selected.getModule();

        // TODO:DAZ It's not clear that we're setting up the correct state here:
        // - We are not updating the selection reasons for the selected component
        // - If the target module changed, we are not updating the set of selectors on the target modules (both current and new)
    }

    public ComponentSelectionReasonInternal getSelectionReason() {
        // Create a component selection reason specific to this selector.
        return addReasonsForSelector(VersionSelectionReasons.empty());
    }

    ComponentSelectionReasonInternal addReasonsForSelector(ComponentSelectionReasonInternal selectionReason) {
        ComponentSelectionDescriptorInternal dependencyDescriptor = dependencyMetadata.isPending() ? CONSTRAINT : REQUESTED;
        if (dependencyMetadata.getReason() != null) {
            dependencyDescriptor = dependencyDescriptor.withReason(dependencyMetadata.getReason());
        }
        selectionReason.addCause(dependencyDescriptor);

        if (dependencyState.getRuleDescriptor() != null) {
            selectionReason.addCause(dependencyState.getRuleDescriptor());
        }
        return selectionReason;
    }

    public DependencyMetadata getDependencyMetadata() {
        return dependencyMetadata;
    }

    public ResolvedVersionConstraint getVersionConstraint() {
        return versionConstraint;
    }

    @Override
    public boolean isForce() {
        return dependencyMetadata instanceof LocalOriginDependencyMetadata
            && ((LocalOriginDependencyMetadata) dependencyMetadata).isForce();
    }

}
