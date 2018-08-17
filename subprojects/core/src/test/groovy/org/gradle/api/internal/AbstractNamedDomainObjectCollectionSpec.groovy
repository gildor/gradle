/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.internal.provider.ProviderInternal
import spock.lang.Unroll

abstract class AbstractNamedDomainObjectCollectionSpec<T> extends AbstractDomainObjectCollectionSpec<T> {
    abstract NamedDomainObjectCollection<T> getContainer()

    @Unroll
    def "allow mutating when getByName(String, #factoryClass.configurationType) calls #description"() {
        def factory = factoryClass.newInstance(this)

        when:
        container.add(a)
        container.getByName("a", factory.create())

        then:
        noExceptionThrown()

        where:
        [description, factoryClass] << getInvalidCallFromLazyConfiguration()
    }

    @Unroll
    def "disallow mutating when named(String).configure(#factoryClass.configurationType) for added element calls #description"() {
        def factory = factoryClass.newInstance(this)

        when:
        container.add(a)
        container.named("a").configure(factory.create())

        then:
        def ex = thrown(IllegalStateException)
        ex.message == "${container.class.simpleName}#${description} on ${container.toString()} cannot be executed in the current context."

        where:
        [description, factoryClass] << getInvalidCallFromLazyConfiguration()
    }

    @Unroll
    def "disallow mutating when named(String).configure(#factoryClass.configurationType) for added element provider calls #description"() {
        containerAllowsExternalProviders()
        def factory = factoryClass.newInstance(this)
        def provider = Mock(NamedProviderInternal)

        given:
        _ * provider.type >> type
        _ * provider.name >> "a"
        _ * provider.get() >> a

        when:
        container.addLater(provider)
        def domainObjectProvider = container.named("a")
        domainObjectProvider.configure(factory.create())
        domainObjectProvider.get() // force realize

        then:
        def ex = thrown(IllegalStateException)
        ex.message == "Could not create domain object 'a' (${type.simpleName})"
        ex.cause.message == "${container.class.simpleName}#${description} on ${container.toString()} cannot be executed in the current context."

        where:
        [description, factoryClass] << getInvalidCallFromLazyConfiguration()
    }

    interface NamedProviderInternal extends Named, ProviderInternal {}
}
