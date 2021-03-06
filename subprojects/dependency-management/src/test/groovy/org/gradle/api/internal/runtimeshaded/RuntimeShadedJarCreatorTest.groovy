/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.api.internal.runtimeshaded

import org.gradle.api.Action
import org.gradle.internal.IoActions
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.util.UsesNativeServices
import org.junit.Rule
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import spock.lang.Specification

import java.util.jar.JarEntry
import java.util.jar.JarFile

@UsesNativeServices
class RuntimeShadedJarCreatorTest extends Specification {

    @Rule
    TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()

    def progressLoggerFactory = Mock(ProgressLoggerFactory)
    def progressLogger = Mock(ProgressLogger)
    def relocatedJarCreator = new RuntimeShadedJarCreator(progressLoggerFactory)
    def outputJar = new File(tmpDir.testDirectory, 'gradle-api.jar')

    def "creates JAR file for input directory"() {
        given:
        def inputFilesDir = tmpDir.createDir('inputFiles')
        writeClass(inputFilesDir, "org/gradle/MyClass")

        when:
        relocatedJarCreator.create(outputJar, [inputFilesDir])

        then:
        1 * progressLoggerFactory.newOperation(RuntimeShadedJarCreator) >> progressLogger
        1 * progressLogger.setDescription('Gradle JARs generation')
        1 * progressLogger.setLoggingHeader("Generating JAR file '$outputJar.name'")
        1 * progressLogger.started()
        1 * progressLogger.progress(_)
        1 * progressLogger.completed()
        TestFile[] contents = tmpDir.testDirectory.listFiles().findAll { it.isFile() }
        contents.length == 1
        contents[0] == outputJar
    }

    def "creates fat JAR file for multiple input JAR files"() {
        given:
        def className = 'org/gradle/MyClass'
        def inputFilesDir = tmpDir.createDir('inputFiles')
        def jarFile1 = inputFilesDir.file('lib1.jar')
        createJarFileWithClassFiles(jarFile1, [className])
        def jarFile2 = inputFilesDir.file('lib2.jar')
        createJarFileWithClassFiles(jarFile2, [className])

        when:
        relocatedJarCreator.create(outputJar, [jarFile1, jarFile2])

        then:
        1 * progressLoggerFactory.newOperation(RuntimeShadedJarCreator) >> progressLogger
        1 * progressLogger.setDescription('Gradle JARs generation')
        1 * progressLogger.setLoggingHeader("Generating JAR file '$outputJar.name'")
        1 * progressLogger.started()
        2 * progressLogger.progress(_)
        1 * progressLogger.completed()
        TestFile[] contents = tmpDir.testDirectory.listFiles().findAll { it.isFile() }
        contents.length == 1
        contents[0] == outputJar
    }

    def "merges provider-configuration file with the same name"() {
        given:
        def inputFilesDir = tmpDir.createDir('inputFiles')
        def serviceType = 'org.gradle.internal.service.scopes.PluginServiceRegistry'
        def jarFile1 = inputFilesDir.file('lib1.jar')
        createJarFileWithProviderConfigurationFile(jarFile1, serviceType, 'org.gradle.api.internal.artifacts.DependencyServices')
        def jarFile2 = inputFilesDir.file('lib2.jar')
        createJarFileWithProviderConfigurationFile(jarFile2, serviceType, """

org.gradle.plugin.use.internal.PluginUsePluginServiceRegistry

""")
        def jarFile3 = inputFilesDir.file('lib3.jar')
        createJarFileWithProviderConfigurationFile(jarFile3, serviceType, """
# This is some same file
# Ignore comment
org.gradle.api.internal.tasks.CompileServices
# Too many comments""")

        when:
        relocatedJarCreator.create(outputJar, [jarFile1, jarFile2, jarFile3])

        then:
        1 * progressLoggerFactory.newOperation(RuntimeShadedJarCreator) >> progressLogger
        1 * progressLogger.setDescription('Gradle JARs generation')
        1 * progressLogger.setLoggingHeader("Generating JAR file '$outputJar.name'")
        1 * progressLogger.started()
        3 * progressLogger.progress(_)
        1 * progressLogger.completed()
        TestFile[] contents = tmpDir.testDirectory.listFiles().findAll { it.isFile() }
        contents.length == 1
        def relocatedJar = contents[0]
        relocatedJar == outputJar

        handleAsJarFile(relocatedJar) { JarFile jar ->
            JarEntry providerConfigJarEntry = jar.getJarEntry("META-INF/services/$serviceType")
            IoActions.withResource(jar.getInputStream(providerConfigJarEntry), new Action<InputStream>() {
                void execute(InputStream inputStream) {
                    assert inputStream.text == """org.gradle.api.internal.artifacts.DependencyServices
org.gradle.plugin.use.internal.PluginUsePluginServiceRegistry
org.gradle.api.internal.tasks.CompileServices"""
                }
            })
        }
    }

    def "relocates Gradle impl dependency classes"() {
        given:
        def noRelocationClassNames = ['org/gradle/MyClass',
                                      'java/lang/String',
                                      'javax/inject/Inject',
                                      'groovy/util/XmlSlurper',
                                      'groovyjarjarantlr/TokenStream',
                                      'net/rubygrapefruit/platform/FileInfo',
                                      'org/codehaus/groovy/ant/Groovyc',
                                      'org/apache/tools/ant/taskdefs/Ant',
                                      'org/slf4j/Logger',
                                      'org/apache/commons/logging/Log',
                                      'org/apache/log4j/Logger',
                                      'org/apache/xerces/parsers/SAXParser',
                                      'org/w3c/dom/Document',
                                      'org/xml/sax/XMLReader']
        def relocationClassNames = ['org/apache/commons/lang3/StringUtils',
                                    'com/google/common/collect/Lists']
        def classNames = noRelocationClassNames + relocationClassNames
        def inputFilesDir = tmpDir.createDir('inputFiles')
        def jarFile = inputFilesDir.file('lib.jar')
        createJarFileWithClassFiles(jarFile, classNames)

        when:
        relocatedJarCreator.create(outputJar, [jarFile])

        then:
        1 * progressLoggerFactory.newOperation(RuntimeShadedJarCreator) >> progressLogger
        1 * progressLogger.setDescription('Gradle JARs generation')
        1 * progressLogger.setLoggingHeader("Generating JAR file '$outputJar.name'")
        1 * progressLogger.started()
        1 * progressLogger.progress(_)
        1 * progressLogger.completed()
        TestFile[] contents = tmpDir.testDirectory.listFiles().findAll { it.isFile() }
        contents.length == 1
        def relocatedJar = contents[0]
        relocatedJar == outputJar

        handleAsJarFile(relocatedJar) { JarFile jar ->
            assert jar.getJarEntry('org/gradle/MyClass.class')
            assert jar.getJarEntry('java/lang/String.class')
            assert jar.getJarEntry('javax/inject/Inject.class')
            assert jar.getJarEntry('groovy/util/XmlSlurper.class')
            assert jar.getJarEntry('groovyjarjarantlr/TokenStream.class')
            assert jar.getJarEntry('net/rubygrapefruit/platform/FileInfo.class')
            assert jar.getJarEntry('org/codehaus/groovy/ant/Groovyc.class')
            assert jar.getJarEntry('org/apache/tools/ant/taskdefs/Ant.class')
            assert jar.getJarEntry('org/slf4j/Logger.class')
            assert jar.getJarEntry('org/apache/commons/logging/Log.class')
            assert jar.getJarEntry('org/apache/log4j/Logger.class')
            assert jar.getJarEntry('org/apache/xerces/parsers/SAXParser.class')
            assert jar.getJarEntry('org/w3c/dom/Document.class')
            assert jar.getJarEntry('org/xml/sax/XMLReader.class')
            assert jar.getJarEntry('org/gradle/internal/impldep/org/apache/commons/lang3/StringUtils.class')
            assert jar.getJarEntry('org/gradle/internal/impldep/com/google/common/collect/Lists.class')
        }
    }

    private void createJarFileWithClassFiles(TestFile jar, List<String> classNames) {
        TestFile contents = tmpDir.createDir("contents/$jar.name")

        classNames.each { className ->
            writeClass(contents, className)
        }

        contents.zipTo(jar)
    }

    private void writeClass(TestFile outputDir, String className) {
        TestFile classFile = outputDir.createFile("${className}.class")
        ClassNode classNode = new ClassNode()
        classNode.version = Opcodes.V1_6
        classNode.access = Opcodes.ACC_PUBLIC
        classNode.name = className
        classNode.superName = 'java/lang/Object'

        ClassWriter cw = new ClassWriter(0)
        classNode.accept(cw)

        classFile.withOutputStream {
            it.write(cw.toByteArray())
        }
    }

    private void createJarFileWithProviderConfigurationFile(TestFile jar, String serviceType, String serviceProvider) {
        TestFile contents = tmpDir.createDir("contents/$jar.name")
        contents.createFile("META-INF/services/$serviceType") << serviceProvider
        contents.zipTo(jar)
    }

    static void handleAsJarFile(TestFile jar, Closure c) {
        def jarFile

        try {
            jarFile = new JarFile(jar)
            c(jarFile)
        } finally {
            if (jarFile != null) {
                jarFile.close()
            }
        }
    }
}
