/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.api.internal.artifacts.ivyservice

import org.apache.ivy.core.module.descriptor.*
import org.apache.ivy.core.module.id.ModuleId
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.plugins.matcher.ExactPatternMatcher
import org.gradle.internal.component.external.model.ModuleDescriptorState
import org.gradle.internal.component.model.DefaultIvyArtifactName
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification

import java.text.SimpleDateFormat

import static org.gradle.api.internal.artifacts.ivyservice.IvyUtil.createModuleId
import static org.gradle.api.internal.artifacts.ivyservice.IvyUtil.createModuleRevisionId

class IvyXmlModuleDescriptorWriterTest extends Specification {

    private @Rule TestNameTestDirectoryProvider temporaryFolder;
    private ModuleDescriptor md = Mock();
    private ModuleRevisionId moduleRevisionId = Mock()
    def ivyXmlModuleDescriptorWriter = new IvyXmlModuleDescriptorWriter()

    def "can create ivy (unmodified) descriptor"() {
        setup:
        assertMockMethodInvocations()
        def dependency1 = mockDependencyDescriptor("Dep1")
        def dependency2 = mockDependencyDescriptor("Dep2")
        1 * dependency2.changing >> true
        1 * dependency1.transitive >> true
        1 * md.dependencies >> [dependency1, dependency2]
        when:
        def descriptor = new ModuleDescriptorState(md)
        descriptor.addArtifact(new DefaultIvyArtifactName("testartifact", "jar", "jar"), ["archives", "runtime"] as Set)
        File ivyFile = temporaryFolder.file("test/ivy/ivy.xml")
        ivyXmlModuleDescriptorWriter.write(descriptor, ivyFile);
        then:
        def ivyModule = new XmlSlurper().parse(ivyFile);
        assert ivyModule.@version == "2.0"
        assert ivyModule.info.@organisation == "org.test"
        assert ivyModule.info.@module == "projectA"
        assert ivyModule.info.@revision == "1.0"
        assert ivyModule.info.@status == "integration"
        assert ivyModule.configurations.conf.collect {it.@name } == ["archives", "compile", "runtime"]
        assert ivyModule.publications.artifact.collect {it.@name } == ["testartifact"]
        assert ivyModule.publications.artifact.collect {it.@conf } == ["archives,runtime"]
        assert ivyModule.dependencies.dependency.collect { "${it.@org}:${it.@name}:${it.@rev}" } == ["org.test:Dep1:1.0", "org.test:Dep2:1.0"]
    }

    private void assertMockMethodInvocations() {
        1 * md.getExtraInfo() >> Collections.emptyMap()
        1 * md.status >> "integration"
        _ * md.moduleRevisionId >> moduleRevisionId;
        _ * moduleRevisionId.organisation >> "org.test"
        _ * moduleRevisionId.name >> "projectA"
        _ * moduleRevisionId.revision >> "1.0"
        _ * md.configurationsNames >> ["archives", "compile", "runtime"]
        _ * md.configurations >> [mockConfiguration("archives"), mockConfiguration("compile"), mockConfiguration("runtime", ["compile"])]
        1 * md.allExcludeRules >> []
        0 * md.allDependencyDescriptorMediators
    }

    def "does not evaluate dependency descriptor mediators"() {
        given:
        ModuleRevisionId moduleRevisionId = createModuleRevisionId('org.test', 'projectA', '2.0')
        ModuleDescriptor moduleDescriptor = DefaultModuleDescriptor.newDefaultInstance(moduleRevisionId)
        ModuleId moduleId = createModuleId('org.test', 'projectA')
        DependencyDescriptorMediator mediator = DefaultModuleDescriptor.newDefaultInstance(moduleRevisionId)
        moduleDescriptor.addDependencyDescriptorMediator(moduleId, new ExactPatternMatcher(), mediator)
        assert moduleDescriptor.allDependencyDescriptorMediators.allRules.size() == 1

        when:
        File ivyFile = temporaryFolder.file("test/ivy/ivy.xml")
        ivyXmlModuleDescriptorWriter.write(new ModuleDescriptorState(moduleDescriptor), ivyFile)

        then:
        def ivyModule = new XmlSlurper().parse(ivyFile)
        notThrown(UnsupportedOperationException)
        assert ivyModule.info.@organisation == 'org.test'
        assert ivyModule.info.@module == 'projectA'
        assert ivyModule.info.@revision == '2.0'
    }

    def date(String timestamp) {
        def format = new SimpleDateFormat("yyyyMMddHHmmss")
        format.parse(timestamp)
    }

    def mockDependencyDescriptor(String organisation = "org.test", String moduleName, String revision = "1.0") {
        DependencyDescriptor dependencyDescriptor = Mock()
        ModuleRevisionId moduleRevisionId = Mock()
        _ * moduleRevisionId.organisation >> organisation
        _ * moduleRevisionId.name >> moduleName
        _ * moduleRevisionId.revision >> revision
        1 * dependencyDescriptor.dependencyRevisionId >> moduleRevisionId
        1 * dependencyDescriptor.dynamicConstraintDependencyRevisionId >> moduleRevisionId
        1 * dependencyDescriptor.moduleConfigurations >> ["default"]
        1 * dependencyDescriptor.getDependencyConfigurations("default") >> ["compile, archives"]
        1 * dependencyDescriptor.allDependencyArtifacts >> []
        1 * dependencyDescriptor.allExcludeRules >> []
        dependencyDescriptor
    }

    def mockConfiguration(String configurationName, List extended = []) {
        Configuration configuration = Mock()
        1 * configuration.name >> configurationName
        1 * configuration.extends >> extended
        1 * configuration.visibility >> Configuration.Visibility.PUBLIC
        configuration
    }
}
