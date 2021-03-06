/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.kie.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.resolver.filter.CumulativeScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.drools.compiler.compiler.io.Folder;
import org.drools.compiler.compiler.io.memory.MemoryFile;
import org.drools.compiler.compiler.io.memory.MemoryFileSystem;
import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.compiler.kie.builder.impl.KieBuilderImpl;
import org.drools.compiler.kie.builder.impl.KieModuleKieProject;
import org.drools.compiler.kie.builder.impl.MemoryKieModule;
import org.drools.compiler.kie.builder.impl.ResultsImpl;
import org.drools.modelcompiler.CanonicalKieModule;
import org.drools.modelcompiler.builder.CanonicalModelKieProject;
import org.drools.modelcompiler.builder.ModelBuilderImpl;
import org.drools.modelcompiler.builder.ModelWriter;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;

import static org.kie.maven.plugin.ExecModelMode.isModelCompilerInClassPath;
import static org.kie.maven.plugin.ExecModelMode.modelParameterEnabled;

@Mojo(name = "generateModel",
        requiresDependencyResolution = ResolutionScope.NONE,
        requiresProject = true,
        defaultPhase = LifecyclePhase.COMPILE)
public class GenerateModelMojo extends AbstractKieMojo {

    public static PathMatcher drlFileMatcher = FileSystems.getDefault().getPathMatcher("glob:**.drl");

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession mavenSession;

    @Parameter(required = true, defaultValue = "${project.build.directory}")
    private File targetDirectory;

    @Parameter(required = true, defaultValue = "${project.basedir}")
    private File projectDir;

    @Parameter(required = true, defaultValue = "${project.build.testSourceDirectory}")
    private File testDir;

    @Parameter
    private Map<String, String> properties;

    @Parameter(required = true, defaultValue = "${project}")
    private MavenProject project;

    @Parameter(required = true, defaultValue = "${project.build.outputDirectory}")
    private File outputDirectory;

    @Parameter(property = "generateModel", defaultValue = "YES_WITHDRL")
    private String generateModel;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // GenerateModelMojo is executed when BuildMojo isn't and vice-versa
        boolean modelParameterEnabled = modelParameterEnabled(generateModel);
        boolean modelCompilerInClassPath = isModelCompilerInClassPath(project.getDependencies());
        if (modelParameterEnabled && modelCompilerInClassPath) {
            generateModel();
        } else if (modelParameterEnabled) { // !modelCompilerInClassPath
            getLog().warn("You're trying to build rule assets in a project from an executable rule model, but you did not provide the required dependency on the project classpath.\n" +
                                  "To enable executable rule models for your project, add the `drools-model-compiler` dependency in the `pom.xml` file of your project.\n");
        }

    }

    private void generateModel() throws MojoExecutionException {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Set<URL> urls = new HashSet<>();
            for (String element : project.getCompileClasspathElements()) {
                urls.add(new File(element).toURI().toURL());
            }

            project.setArtifactFilter(new CumulativeScopeArtifactFilter(Arrays.asList("compile",
                                                                                      "runtime")));
            for (Artifact artifact : project.getArtifacts()) {
                File file = artifact.getFile();
                if (file != null) {
                    urls.add(file.toURI().toURL());
                }
            }
            urls.add(outputDirectory.toURI().toURL());

            ClassLoader projectClassLoader = URLClassLoader.newInstance(urls.toArray(new URL[0]),
                                                                        getClass().getClassLoader());

            Thread.currentThread().setContextClassLoader(projectClassLoader);
        } catch (DependencyResolutionRequiredException | MalformedURLException e) {
            throw new RuntimeException(e);
        }

        try {
            setSystemProperties(properties);

            KieServices ks = KieServices.Factory.get();
            final KieBuilderImpl kieBuilder = (KieBuilderImpl) ks.newKieBuilder(projectDir);
            kieBuilder.setPomModel(new ProjectPomModel(mavenSession));
            kieBuilder.buildAll(ExecutableModelMavenProject.SUPPLIER,
                                s -> !s.contains("src/test/java") && !s.contains("src\\test\\java"));

            InternalKieModule kieModule = (InternalKieModule) kieBuilder.getKieModule();
            List<String> generatedFiles = kieModule.getFileNames()
                    .stream()
                    .filter(f -> f.endsWith("java"))
                    .collect(Collectors.toList());


            Set<String> drlFiles = kieModule.getFileNames()
                    .stream()
                    .filter(f -> f.endsWith("drl"))
                    .collect(Collectors.toSet());

            getLog().info(String.format("Found %d generated files in Canonical Model", generatedFiles.size()));

            MemoryFileSystem mfs = kieModule instanceof CanonicalKieModule ?
                    ((MemoryKieModule) ((CanonicalKieModule) kieModule).getInternalKieModule()).getMemoryFileSystem() :
                    ((MemoryKieModule) kieModule).getMemoryFileSystem();

            final String droolsModelCompilerPath = "/generated-sources/drools-model-compiler/main/java";
            final String newCompileSourceRoot = targetDirectory.getPath() + droolsModelCompilerPath;
            project.addCompileSourceRoot(newCompileSourceRoot);

            for (String generatedFile : generatedFiles) {
                final MemoryFile f = (MemoryFile) mfs.getFile(generatedFile);
                final Path newFile = Paths.get(targetDirectory.getPath(),
                                               droolsModelCompilerPath,
                                               f.getPath().toPortableString());

                try {
                    Files.deleteIfExists(newFile);
                    Files.createDirectories(newFile.getParent());
                    Files.copy(f.getContents(), newFile, StandardCopyOption.REPLACE_EXISTING);

                    getLog().info("Generating " + newFile);
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new MojoExecutionException("Unable to write file", e);
                }
            }

            // copy the META-INF packages file
            final String path = CanonicalKieModule.getModelFileWithGAV(kieModule.getReleaseId());
            final MemoryFile packagesMemoryFile = (MemoryFile) mfs.getFile(path);
            final String packagesMemoryFilePath = packagesMemoryFile.getFolder().getPath().toPortableString();
            final Path packagesDestinationPath = Paths.get(targetDirectory.getPath(), "classes", packagesMemoryFilePath, packagesMemoryFile.getName());

            try {
                if (!Files.exists(packagesDestinationPath)) {
                    Files.createDirectories(packagesDestinationPath.getParent());
                }
                Files.copy(packagesMemoryFile.getContents(), packagesDestinationPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
                throw new MojoExecutionException("Unable to write file", e);
            }

            if (ExecModelMode.shouldDeleteFile(generateModel)) {
                deleteDrlFiles(drlFiles);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }

        getLog().info("DSL successfully generated");
    }

    private void deleteDrlFiles(Set<String> actualDrlFiles) throws MojoExecutionException {
        // Remove drl files
        try (final Stream<Path> drlFilesToDeleted = Files.find(outputDirectory.toPath(), Integer.MAX_VALUE, (p, f) -> drlFileMatcher.matches(p))) {
            Set<String> deletedFiles = new HashSet<>();
            drlFilesToDeleted.forEach(p -> {
                try {
                    Files.delete(p);
                    deletedFiles.add(p.toString());
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new RuntimeException("Unable to delete file " + p);
                }
            });
            actualDrlFiles.retainAll(deletedFiles);
            if(!actualDrlFiles.isEmpty()) {
                String actualDrlFiles1 = String.join(",", actualDrlFiles);
                getLog().warn("Base directory: " + projectDir);
                getLog().warn("Files not deleted: " + actualDrlFiles1);
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new MojoExecutionException("Unable to find .drl files");
        }
    }

    public static class ExecutableModelMavenProject implements KieBuilder.ProjectType {

        public static final BiFunction<InternalKieModule, ClassLoader, KieModuleKieProject> SUPPLIER = ExecutableModelMavenPluginKieProject::new;

        public static class ExecutableModelMavenPluginKieProject extends CanonicalModelKieProject {

            public ExecutableModelMavenPluginKieProject(InternalKieModule kieModule, ClassLoader classLoader) {
                super(true, kieModule, classLoader);
            }

            @Override
            public void writeProjectOutput(MemoryFileSystem trgMfs, ResultsImpl messages) {
                MemoryFileSystem srcMfs = new MemoryFileSystem();
                List<String> modelFiles = new ArrayList<>();
                ModelWriter modelWriter = new ModelWriter();
                for (ModelBuilderImpl modelBuilder : modelBuilders) {
                    ModelWriter.Result result = modelWriter.writeModel(srcMfs, modelBuilder.getPackageSources());
                    modelFiles.addAll(result.getModelFiles());
                    final Folder sourceFolder = srcMfs.getFolder("src/main/java");
                    final Folder targetFolder = trgMfs.getFolder(".");
                    srcMfs.copyFolder(sourceFolder, trgMfs, targetFolder);
                }
                modelWriter.writeModelFile(modelFiles, trgMfs, getInternalKieModule().getReleaseId());
            }
        }
    }
}
