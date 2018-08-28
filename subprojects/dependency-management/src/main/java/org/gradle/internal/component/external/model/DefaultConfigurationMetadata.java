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

package org.gradle.internal.component.external.model;

import com.google.common.collect.ImmutableList;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.capabilities.CapabilitiesMetadata;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.component.model.ComponentResolveMetadata;
import org.gradle.internal.component.model.ConfigurationMetadata;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.component.model.ExcludeMetadata;
import org.gradle.internal.component.model.ForcingDependencyMetadata;
import org.gradle.internal.component.model.IvyArtifactName;

import java.util.List;

/**
 * Effectively immutable implementation of ConfigurationMetadata.
 * Used to represent Ivy and Maven modules in the dependency graph.
 */
public class DefaultConfigurationMetadata extends AbstractConfigurationMetadata {

    private final VariantMetadataRules componentMetadataRules;

    private List<ModuleDependencyMetadata> calculatedDependencies;

    // Could be precomputed, but we avoid doing so if attributes are never requested
    private ImmutableAttributes computedAttributes;
    private CapabilitiesMetadata computedCapabilities;

    public DefaultConfigurationMetadata(ModuleComponentIdentifier componentId, String name, boolean transitive, boolean visible,
                                           ImmutableList<String> hierarchy, ImmutableList<? extends ModuleComponentArtifactMetadata> artifacts,
                                           VariantMetadataRules componentMetadataRules,
                                           ImmutableList<ExcludeMetadata> excludes,
                                           ImmutableAttributes componentLevelAttributes) {
        this(componentId, name, transitive, visible, hierarchy, artifacts, componentMetadataRules, excludes, componentLevelAttributes, null);
    }

    private DefaultConfigurationMetadata(ModuleComponentIdentifier componentId, String name, boolean transitive, boolean visible,
                                         ImmutableList<String> hierarchy, ImmutableList<? extends ModuleComponentArtifactMetadata> artifacts,
                                         VariantMetadataRules componentMetadataRules,
                                         ImmutableList<ExcludeMetadata> excludes,
                                         ImmutableAttributes attributes,
                                         ImmutableList<ModuleDependencyMetadata> configDependencies) {
        super(componentId, name, transitive, visible, artifacts, hierarchy, excludes, attributes, configDependencies, ImmutableCapabilities.EMPTY);
        this.componentMetadataRules = componentMetadataRules;
    }

    @Override
    public ImmutableAttributes getAttributes() {
        if (computedAttributes == null) {
            computedAttributes = componentMetadataRules.applyVariantAttributeRules(this, super.getAttributes());
        }
        return computedAttributes;
    }

    @Override
    public List<? extends DependencyMetadata> getDependencies() {
        if (calculatedDependencies == null) {
            calculatedDependencies = componentMetadataRules.applyDependencyMetadataRules(this, getConfigDependencies());
        }
        return calculatedDependencies;
    }

    @Override
    public CapabilitiesMetadata getCapabilities() {
        if (computedCapabilities == null) {
            computedCapabilities = componentMetadataRules.applyCapabilitiesRules(this, super.getCapabilities());
        }
        return computedCapabilities;
    }

    public DefaultConfigurationMetadata withAttributes(ImmutableAttributes attributes) {
        return new DefaultConfigurationMetadata(getComponentId(), getName(), isTransitive(), isVisible(), ImmutableList.copyOf(getHierarchy()), ImmutableList.copyOf(getArtifacts()), componentMetadataRules, getExcludes(), attributes, getConfigDependencies());
    }

    public DefaultConfigurationMetadata withAttributes(String newName, ImmutableAttributes attributes) {
        return new DefaultConfigurationMetadata(getComponentId(), newName, isTransitive(), isVisible(), ImmutableList.copyOf(getHierarchy()), ImmutableList.copyOf(getArtifacts()), componentMetadataRules, getExcludes(), attributes, getConfigDependencies());
    }

    public DefaultConfigurationMetadata withForcedDependencies() {
        return new DefaultConfigurationMetadata(getComponentId(), getName(), isTransitive(), isVisible(), ImmutableList.copyOf(getHierarchy()), ImmutableList.copyOf(getArtifacts()), componentMetadataRules, getExcludes(), getAttributes(), force(getConfigDependencies()));
    }

    private ImmutableList<ModuleDependencyMetadata> force(ImmutableList<ModuleDependencyMetadata> configDependencies) {
        ImmutableList.Builder<ModuleDependencyMetadata> dependencies = new ImmutableList.Builder<ModuleDependencyMetadata>();
        for (ModuleDependencyMetadata configDependency : configDependencies) {
            if (configDependency instanceof ForcingDependencyMetadata) {
                dependencies.add((ModuleDependencyMetadata) ((ForcingDependencyMetadata) configDependency).forced());
            } else {
                dependencies.add(new ForcedDependencyMetadataWrapper(configDependency));
            }
        }
        return dependencies.build();
    }

    public DefaultConfigurationMetadata withoutPending() {
        return withPending(false);
    }

    public DefaultConfigurationMetadata withPendingOnly() {
        return withPending(true);
    }

    private DefaultConfigurationMetadata withPending(boolean pending) {
        ImmutableList<ModuleDependencyMetadata> configDependencies = getConfigDependencies();
        ImmutableList.Builder<ModuleDependencyMetadata> dependenciesOnly = new ImmutableList.Builder<ModuleDependencyMetadata>();
        for (ModuleDependencyMetadata configDependency : configDependencies) {
            if (configDependency.isPending() == pending) {
                dependenciesOnly.add(configDependency);
            }
        }
        return new DefaultConfigurationMetadata(getComponentId(), getName(), isTransitive(), isVisible(), ImmutableList.copyOf(getHierarchy()), ImmutableList.copyOf(getArtifacts()), componentMetadataRules, getExcludes(), getAttributes(), dependenciesOnly.build());
    }

    private static class ForcedDependencyMetadataWrapper implements ForcingDependencyMetadata, ModuleDependencyMetadata {
        private final ModuleDependencyMetadata delegate;

        private ForcedDependencyMetadataWrapper(ModuleDependencyMetadata delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModuleComponentSelector getSelector() {
            return delegate.getSelector();
        }

        @Override
        public ModuleDependencyMetadata withRequestedVersion(VersionConstraint requestedVersion) {
            return new ForcedDependencyMetadataWrapper(delegate.withRequestedVersion(requestedVersion));
        }

        @Override
        public ModuleDependencyMetadata withReason(String reason) {
            return new ForcedDependencyMetadataWrapper(delegate.withReason(reason));
        }

        @Override
        public List<ConfigurationMetadata> selectConfigurations(ImmutableAttributes consumerAttributes, ComponentResolveMetadata targetComponent, AttributesSchemaInternal consumerSchema) {
            return delegate.selectConfigurations(consumerAttributes, targetComponent, consumerSchema);
        }

        @Override
        public List<ExcludeMetadata> getExcludes() {
            return delegate.getExcludes();
        }

        @Override
        public List<IvyArtifactName> getArtifacts() {
            return delegate.getArtifacts();
        }

        @Override
        public DependencyMetadata withTarget(ComponentSelector target) {
            return new ForcedDependencyMetadataWrapper((ModuleDependencyMetadata) delegate.withTarget(target));
        }

        @Override
        public boolean isChanging() {
            return delegate.isChanging();
        }

        @Override
        public boolean isTransitive() {
            return delegate.isTransitive();
        }

        @Override
        public boolean isPending() {
            return delegate.isPending();
        }

        @Override
        public String getReason() {
            return delegate.getReason();
        }

        @Override
        public boolean isForce() {
            return true;
        }

        @Override
        public ForcingDependencyMetadata forced() {
            return this;
        }
    }
}
