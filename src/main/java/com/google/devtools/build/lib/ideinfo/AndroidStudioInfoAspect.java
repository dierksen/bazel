// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.ideinfo;

import static com.google.common.collect.Iterables.transform;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.Aspect;
import com.google.devtools.build.lib.analysis.Aspect.Builder;
import com.google.devtools.build.lib.analysis.ConfiguredAspectFactory;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.BinaryFileWriteAction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.AndroidRuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.AndroidSdkRuleInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.ArtifactLocation;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.JavaRuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.LibraryArtifact;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.RuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.RuleIdeInfo.Kind;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.android.AndroidCommon;
import com.google.devtools.build.lib.rules.android.AndroidIdeInfoProvider;
import com.google.devtools.build.lib.rules.android.AndroidIdeInfoProvider.SourceDirectory;
import com.google.devtools.build.lib.rules.android.AndroidSdkProvider;
import com.google.devtools.build.lib.rules.java.JavaExportsProvider;
import com.google.devtools.build.lib.rules.java.JavaGenJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaSourceInfoProvider;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Generates ide-build information for Android Studio.
 */
public class AndroidStudioInfoAspect implements ConfiguredAspectFactory {
  public static final String NAME = "AndroidStudioInfoAspect";

  // Output groups.
  public static final String IDE_RESOLVE = "ide-resolve";
  public static final String IDE_BUILD = "ide-build";

  // File suffixes.
  public static final String ASWB_BUILD_SUFFIX = ".aswb-build";
  public static final Function<Label, String> LABEL_TO_STRING = new Function<Label, String>() {
    @Nullable
    @Override
    public String apply(Label label) {
      return label.toString();
    }
  };

  @Override
  public AspectDefinition getDefinition() {
    return new AspectDefinition.Builder(NAME)
        .attributeAspect("deps", AndroidStudioInfoAspect.class)
        .attributeAspect("exports", AndroidStudioInfoAspect.class)
        .build();
  }

  @Override
  public Aspect create(ConfiguredTarget base, RuleContext ruleContext,
      AspectParameters parameters) {
    Aspect.Builder builder = new Builder(NAME);

    AndroidStudioInfoFilesProvider.Builder providerBuilder =
        new AndroidStudioInfoFilesProvider.Builder();

    RuleIdeInfo.Kind ruleKind = getRuleKind(ruleContext.getRule(), base);

    NestedSet<Label> directDependencies = processDependencies(
        base, ruleContext, providerBuilder, ruleKind);

    AndroidStudioInfoFilesProvider provider;
    if (ruleKind != RuleIdeInfo.Kind.UNRECOGNIZED) {
      provider =
          createIdeBuildArtifact(
              base,
              ruleContext,
              ruleKind,
              directDependencies,
              providerBuilder);
    } else {
      provider = providerBuilder.build();
    }

    NestedSet<Artifact> ideInfoFiles = provider.getIdeBuildFiles();
    builder
        .addOutputGroup(IDE_BUILD, ideInfoFiles)
        .addProvider(
            AndroidStudioInfoFilesProvider.class,
            provider);

    return builder.build();
  }

  private NestedSet<Label> processDependencies(
      ConfiguredTarget base, RuleContext ruleContext,
      AndroidStudioInfoFilesProvider.Builder providerBuilder, RuleIdeInfo.Kind ruleKind) {
    NestedSetBuilder<Label> dependenciesBuilder = NestedSetBuilder.stableOrder();

    ImmutableList.Builder<TransitiveInfoCollection> prerequisitesBuilder = ImmutableList.builder();
    if (ruleContext.attributes().has("deps", BuildType.LABEL_LIST)) {
      prerequisitesBuilder.addAll(ruleContext.getPrerequisites("deps", Mode.TARGET));
    }
    if (ruleContext.attributes().has("exports", BuildType.LABEL_LIST)) {
      prerequisitesBuilder.addAll(ruleContext.getPrerequisites("exports", Mode.TARGET));
    }
    List<TransitiveInfoCollection> prerequisites = prerequisitesBuilder.build();

    for (AndroidStudioInfoFilesProvider depProvider :
        AnalysisUtils.getProviders(prerequisites, AndroidStudioInfoFilesProvider.class)) {
      dependenciesBuilder.addTransitive(depProvider.getExportedDeps());

      providerBuilder.ideBuildFilesBuilder().addTransitive(depProvider.getIdeBuildFiles());
      providerBuilder.transitiveDependenciesBuilder().addTransitive(
          depProvider.getTransitiveDependencies());
      providerBuilder.transitiveResourcesBuilder().addTransitive(
          depProvider.getTransitiveResources());
    }
    for (TransitiveInfoCollection dep : prerequisites) {
      dependenciesBuilder.add(dep.getLabel());
    }

    JavaExportsProvider javaExportsProvider = base.getProvider(JavaExportsProvider.class);
    if (javaExportsProvider != null) {
      providerBuilder.exportedDepsBuilder()
          .addTransitive(javaExportsProvider.getTransitiveExports());
    }

    // android_library without sources exports all its deps
    if (ruleKind == Kind.ANDROID_LIBRARY) {
      JavaSourceInfoProvider sourceInfoProvider = base.getProvider(JavaSourceInfoProvider.class);
      boolean hasSources = sourceInfoProvider != null
          && !sourceInfoProvider.getSourceFiles().isEmpty();
      if (!hasSources) {
        for (TransitiveInfoCollection dep : prerequisites) {
          providerBuilder.exportedDepsBuilder().add(dep.getLabel());
        }
      }
    }

    NestedSet<Label> directDependencies = dependenciesBuilder.build();
    providerBuilder.transitiveDependenciesBuilder().addTransitive(directDependencies);
    return directDependencies;
  }

  private static AndroidSdkRuleInfo makeAndroidSdkRuleInfo(RuleContext ruleContext,
      AndroidSdkProvider provider) {
    AndroidSdkRuleInfo.Builder sdkInfoBuilder = AndroidSdkRuleInfo.newBuilder();

    Path androidSdkDirectory = provider.getAndroidJar().getPath().getParentDirectory();
    sdkInfoBuilder.setAndroidSdkPath(androidSdkDirectory.toString());

    Root genfilesDirectory = ruleContext.getConfiguration().getGenfilesDirectory();
    sdkInfoBuilder.setGenfilesPath(genfilesDirectory.getPath().toString());

    Path binfilesPath = ruleContext.getConfiguration().getBinDirectory().getPath();
    sdkInfoBuilder.setBinPath(binfilesPath.toString());

    return sdkInfoBuilder.build();
  }

  private AndroidStudioInfoFilesProvider createIdeBuildArtifact(
      ConfiguredTarget base,
      RuleContext ruleContext,
      Kind ruleKind,
      NestedSet<Label> directDependencies,
      AndroidStudioInfoFilesProvider.Builder providerBuilder) {
    PathFragment ideBuildFilePath = getOutputFilePath(base, ruleContext);
    Root genfilesDirectory = ruleContext.getConfiguration().getGenfilesDirectory();
    Artifact ideBuildFile =
        ruleContext
            .getAnalysisEnvironment()
            .getDerivedArtifact(ideBuildFilePath, genfilesDirectory);
    providerBuilder.ideBuildFilesBuilder().add(ideBuildFile);

    RuleIdeInfo.Builder outputBuilder = RuleIdeInfo.newBuilder();

    outputBuilder.setLabel(base.getLabel().toString());

    outputBuilder.setBuildFile(
        ruleContext
            .getRule()
            .getPackage()
            .getBuildFile()
            .getPath()
            .toString());

    outputBuilder.setKind(ruleKind);


    if (ruleKind == Kind.JAVA_LIBRARY
        || ruleKind == Kind.JAVA_IMPORT
        || ruleKind == Kind.JAVA_TEST
        || ruleKind == Kind.JAVA_BINARY
        || ruleKind == Kind.ANDROID_LIBRARY
        || ruleKind == Kind.ANDROID_BINARY
        || ruleKind == Kind.ANDROID_TEST
        || ruleKind == Kind.ANDROID_ROBOELECTRIC_TEST) {
      outputBuilder.setJavaRuleIdeInfo(makeJavaRuleIdeInfo(base));
    }
    if (ruleKind == Kind.ANDROID_LIBRARY
        || ruleKind == Kind.ANDROID_BINARY
        || ruleKind == Kind.ANDROID_TEST) {
      outputBuilder.setAndroidRuleIdeInfo(
          makeAndroidRuleIdeInfo(ruleContext, base, providerBuilder));
    }
    if (ruleKind == Kind.ANDROID_SDK) {
      outputBuilder.setAndroidSdkRuleInfo(
          makeAndroidSdkRuleInfo(ruleContext, base.getProvider(AndroidSdkProvider.class)));
    }

    AndroidStudioInfoFilesProvider provider = providerBuilder.build();

    outputBuilder.addAllDependencies(transform(directDependencies, LABEL_TO_STRING));
    outputBuilder.addAllTransitiveDependencies(
        transform(provider.getTransitiveDependencies(), LABEL_TO_STRING));

    outputBuilder.addAllTags(base.getTarget().getAssociatedRule().getRuleTags());

    final RuleIdeInfo ruleIdeInfo = outputBuilder.build();
    ruleContext.registerAction(
        makeProtoWriteAction(ruleContext.getActionOwner(), ruleIdeInfo, ideBuildFile));

    return provider;
  }

  private static AndroidRuleIdeInfo makeAndroidRuleIdeInfo(
      RuleContext ruleContext,
      ConfiguredTarget base,
      AndroidStudioInfoFilesProvider.Builder providerBuilder) {
    AndroidRuleIdeInfo.Builder builder = AndroidRuleIdeInfo.newBuilder();
    AndroidIdeInfoProvider provider = base.getProvider(AndroidIdeInfoProvider.class);
    if (provider.getSignedApk() != null) {
      builder.setApk(makeArtifactLocation(provider.getSignedApk()));
    }

    if (provider.getManifest() != null) {
      builder.setManifest(makeArtifactLocation(provider.getManifest()));
    }

    if (provider.getGeneratedManifest() != null) {
      builder.setGeneratedManifest(makeArtifactLocation(provider.getGeneratedManifest()));
    }

    for (Artifact artifact : provider.getApksUnderTest()) {
      builder.addDependencyApk(makeArtifactLocation(artifact));
    }
    for (SourceDirectory resourceDir : provider.getResourceDirs()) {
      ArtifactLocation artifactLocation = makeArtifactLocation(resourceDir);
      builder.addResources(artifactLocation);
      providerBuilder.transitiveResourcesBuilder().add(resourceDir);
    }

    builder.setJavaPackage(AndroidCommon.getJavaPackage(ruleContext));

    NestedSet<SourceDirectory> transitiveResources = providerBuilder.getTransitiveResources();
    for (SourceDirectory transitiveResource : transitiveResources) {
      builder.addTransitiveResources(makeArtifactLocation(transitiveResource));
    }

    boolean hasIdlSources = !provider.getIdlSrcs().isEmpty();
    builder.setHasIdlSources(hasIdlSources);
    if (hasIdlSources) {
      LibraryArtifact.Builder jarBuilder = LibraryArtifact.newBuilder();
      Artifact idlClassJar = provider.getIdlClassJar();
      if (idlClassJar != null) {
        jarBuilder.setJar(makeArtifactLocation(idlClassJar));
      }
      Artifact idlSourceJar = provider.getIdlSourceJar();
      if (idlSourceJar != null) {
        jarBuilder.setSourceJar(makeArtifactLocation(idlSourceJar));
      }
      if (idlClassJar != null) {
        builder.setIdlJar(jarBuilder.build());
      }
    }

    return builder.build();
  }

  private static BinaryFileWriteAction makeProtoWriteAction(
      ActionOwner actionOwner, final MessageLite message, Artifact artifact) {
    return new BinaryFileWriteAction(
        actionOwner,
        artifact,
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            return message.toByteString().newInput();
          }
        },
        /*makeExecutable =*/ false);
  }

  private static ArtifactLocation makeArtifactLocation(Artifact artifact) {
    return ArtifactLocation.newBuilder()
        .setRootPath(artifact.getRoot().getPath().toString())
        .setRelativePath(artifact.getRootRelativePathString())
        .build();
  }

  private static ArtifactLocation makeArtifactLocation(SourceDirectory resourceDir) {
    return ArtifactLocation.newBuilder()
        .setRootPath(resourceDir.getRootPath().toString())
        .setRelativePath(resourceDir.getRelativePath().toString())
        .build();
  }

  private static JavaRuleIdeInfo makeJavaRuleIdeInfo(ConfiguredTarget base) {
    JavaRuleIdeInfo.Builder builder = JavaRuleIdeInfo.newBuilder();
    JavaRuleOutputJarsProvider outputJarsProvider =
        base.getProvider(JavaRuleOutputJarsProvider.class);
    if (outputJarsProvider != null) {
      // java_library
      collectJarsFromOutputJarsProvider(builder, outputJarsProvider);
    } else {
      JavaSourceInfoProvider provider = base.getProvider(JavaSourceInfoProvider.class);
      if (provider != null) {
        // java_import
        collectJarsFromSourceInfoProvider(builder, provider);
      }
    }

    JavaGenJarsProvider genJarsProvider =
        base.getProvider(JavaGenJarsProvider.class);
    if (genJarsProvider != null) {
      collectGenJars(builder, genJarsProvider);
    }

    Collection<Artifact> sourceFiles = getSources(base);

    for (Artifact sourceFile : sourceFiles) {
      builder.addSources(makeArtifactLocation(sourceFile));
    }

    return builder.build();
  }

  private static void collectJarsFromSourceInfoProvider(
      JavaRuleIdeInfo.Builder builder, JavaSourceInfoProvider provider) {
    Collection<Artifact> sourceJarsForJarFiles = provider.getSourceJarsForJarFiles();
    // For java_import rule, we always have only one source jar specified.
    // The intent is that that source jar provides sources for all imported jars,
    // so we reflect that intent, adding that jar to all LibraryArtifacts we produce
    // for java_import rule. We should consider supporting
    //    library=<collection of jars>+<collection of srcjars>
    // mode in our AndroidStudio plugin (Android Studio itself supports that).
    Artifact sourceJar;
    if (sourceJarsForJarFiles.size() > 0) {
      sourceJar = sourceJarsForJarFiles.iterator().next();
    } else {
      sourceJar = null;
    }

    for (Artifact artifact : provider.getJarFiles()) {
      LibraryArtifact.Builder libraryBuilder = LibraryArtifact.newBuilder();
      libraryBuilder.setJar(makeArtifactLocation(artifact));
      if (sourceJar != null) {
        libraryBuilder.setSourceJar(makeArtifactLocation(sourceJar));
      }
      builder.addJars(libraryBuilder.build());
    }
  }

  private static void collectJarsFromOutputJarsProvider(
      JavaRuleIdeInfo.Builder builder, JavaRuleOutputJarsProvider outputJarsProvider) {
    LibraryArtifact.Builder jarsBuilder = LibraryArtifact.newBuilder();
    Artifact classJar = outputJarsProvider.getClassJar();
    if (classJar != null) {
      jarsBuilder.setJar(makeArtifactLocation(classJar));
    }
    Artifact iJar = outputJarsProvider.getIJar();
    if (iJar != null) {
      jarsBuilder.setInterfaceJar(makeArtifactLocation(iJar));
    }
    Artifact srcJar = outputJarsProvider.getSrcJar();
    if (srcJar != null) {
      jarsBuilder.setSourceJar(makeArtifactLocation(srcJar));
    }

    // We don't want to add anything that doesn't have a class jar
    if (classJar != null) {
      builder.addJars(jarsBuilder.build());
    }
  }

  private static void collectGenJars(JavaRuleIdeInfo.Builder builder,
      JavaGenJarsProvider genJarsProvider) {
    LibraryArtifact.Builder genjarsBuilder = LibraryArtifact.newBuilder();

    if (genJarsProvider.usesAnnotationProcessing()) {
      Artifact genClassJar = genJarsProvider.getGenClassJar();
      if (genClassJar != null) {
        genjarsBuilder.setJar(makeArtifactLocation(genClassJar));
      }
      Artifact gensrcJar = genJarsProvider.getGenSourceJar();
      if (gensrcJar != null) {
        genjarsBuilder.setSourceJar(makeArtifactLocation(gensrcJar));
      }
      if (genjarsBuilder.hasJar()) {
        builder.addGeneratedJars(genjarsBuilder.build());
      }
    }
  }

  private static Collection<Artifact> getSources(ConfiguredTarget base) {
    // Calculate source files.
    JavaSourceInfoProvider sourceInfoProvider = base.getProvider(JavaSourceInfoProvider.class);
    if (sourceInfoProvider == null) {
      return ImmutableList.of();
    }
    AndroidIdeInfoProvider androidIdeInfoProvider = base.getProvider(AndroidIdeInfoProvider.class);
    if (androidIdeInfoProvider == null) {
      return sourceInfoProvider.getSourceFiles();
    }
    ImmutableList.Builder<Artifact> builder = ImmutableList.builder();
    Collection<Artifact> idlGeneratedJavaFiles = androidIdeInfoProvider.getIdlGeneratedJavaFiles();
    for (Artifact artifact : sourceInfoProvider.getSourceFiles()) {
      if (!idlGeneratedJavaFiles.contains(artifact)) {
        builder.add(artifact);
      }
    }
    return builder.build();
  }

  private PathFragment getOutputFilePath(ConfiguredTarget base, RuleContext ruleContext) {
    PathFragment packagePathFragment =
        ruleContext.getLabel().getPackageIdentifier().getPathFragment();
    String name = base.getLabel().getName();
    return new PathFragment(packagePathFragment, new PathFragment(name + ASWB_BUILD_SUFFIX));
  }

  private RuleIdeInfo.Kind getRuleKind(Rule rule, ConfiguredTarget base) {
    switch (rule.getRuleClassObject().getName()) {
      case "java_library":
        return Kind.JAVA_LIBRARY;
      case "java_import":
        return Kind.JAVA_IMPORT;
      case "java_test":
        return Kind.JAVA_TEST;
      case "java_binary":
        return Kind.JAVA_BINARY;
      case "android_library":
        return Kind.ANDROID_LIBRARY;
      case "android_binary":
        return Kind.ANDROID_BINARY;
      case "android_test":
        return Kind.ANDROID_TEST;
      case "android_robolectric_test":
        return Kind.ANDROID_ROBOELECTRIC_TEST;
      default:
        {
          if (base.getProvider(AndroidSdkProvider.class) != null) {
            return RuleIdeInfo.Kind.ANDROID_SDK;
          } else {
            return RuleIdeInfo.Kind.UNRECOGNIZED;
          }
        }
    }
  }
}
