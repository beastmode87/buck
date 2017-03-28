/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.immutables.value.Value;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;

class AndroidBinaryResourcesGraphEnhancer {
  static final Flavor RESOURCES_FILTER_FLAVOR = InternalFlavor.of("resources_filter");
  static final Flavor AAPT_PACKAGE_FLAVOR = InternalFlavor.of("aapt_package");
  private static final Flavor AAPT2_LINK_FLAVOR = InternalFlavor.of("aapt2_link");
  static final Flavor PACKAGE_STRING_ASSETS_FLAVOR =
      InternalFlavor.of("package_string_assets");
  private final SourcePathRuleFinder ruleFinder;
  private final FilterResourcesStep.ResourceFilter resourceFilter;
  private final ResourcesFilter.ResourceCompressionMode resourceCompressionMode;
  private final ImmutableSet<String> locales;
  private final BuildRuleParams buildRuleParams;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver pathResolver;
  private final AndroidBinary.AaptMode aaptMode;
  private final SourcePath manifest;
  private final Optional<String> resourceUnionPackage;
  private final boolean shouldBuildStringSourceMap;
  private final boolean skipCrunchPngs;
  private final boolean includesVectorDrawables;
  private final EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes;
  private final ManifestEntries manifestEntries;
  private final BuildTarget originalBuildTarget;

  public AndroidBinaryResourcesGraphEnhancer(
      BuildRuleParams buildRuleParams,
      BuildRuleResolver ruleResolver,
      BuildTarget originalBuildTarget,
      SourcePath manifest,
      AndroidBinary.AaptMode aaptMode,
      FilterResourcesStep.ResourceFilter resourceFilter,
      ResourcesFilter.ResourceCompressionMode resourceCompressionMode,
      ImmutableSet<String> locales,
      Optional<String> resourceUnionPackage,
      boolean shouldBuildStringSourceMap,
      boolean skipCrunchPngs,
      boolean includesVectorDrawables,
      EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes,
      ManifestEntries manifestEntries) {
    this.ruleResolver = ruleResolver;
    this.ruleFinder = new SourcePathRuleFinder(ruleResolver);
    this.pathResolver = new SourcePathResolver(ruleFinder);
    this.resourceFilter = resourceFilter;
    this.resourceCompressionMode = resourceCompressionMode;
    this.locales = locales;
    this.buildRuleParams = buildRuleParams;
    this.aaptMode = aaptMode;
    this.manifest = manifest;
    this.resourceUnionPackage = resourceUnionPackage;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.skipCrunchPngs = skipCrunchPngs;
    this.includesVectorDrawables = includesVectorDrawables;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.manifestEntries = manifestEntries;
    this.originalBuildTarget = originalBuildTarget;
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractAndroidBinaryResourcesGraphEnhancementResult {
    AaptOutputInfo getAaptOutputInfo();
    Optional<PackageStringAssets> getPackageStringAssets();
    ImmutableList<BuildRule> getEnhancedDeps();
  }

  public AndroidBinaryResourcesGraphEnhancementResult enhance(
      AndroidPackageableCollection packageableCollection) throws NoSuchBuildTargetException {
    ImmutableList.Builder<BuildRule> enhancedDeps = ImmutableList.builder();
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        packageableCollection.getResourceDetails();

    ImmutableSortedSet<BuildRule> resourceRules =
        getTargetsAsRules(resourceDetails.getResourcesWithNonEmptyResDir());

    ImmutableCollection<BuildRule> rulesWithResourceDirectories =
        ruleFinder.filterBuildRuleInputs(resourceDetails.getResourceDirectories());

    FilteredResourcesProvider filteredResourcesProvider;
    boolean needsResourceFiltering = resourceFilter.isEnabled() ||
        resourceCompressionMode.isStoreStringsAsAssets() ||
        !locales.isEmpty();

    if (needsResourceFiltering) {
      BuildRuleParams paramsForResourcesFilter =
          buildRuleParams
              .withAppendedFlavor(RESOURCES_FILTER_FLAVOR)
              .copyReplacingDeclaredAndExtraDeps(
                  Suppliers.ofInstance(
                      ImmutableSortedSet.<BuildRule>naturalOrder()
                          .addAll(resourceRules)
                          .addAll(rulesWithResourceDirectories)
                          .build()),
                  Suppliers.ofInstance(ImmutableSortedSet.of()));
      ResourcesFilter resourcesFilter = new ResourcesFilter(
          paramsForResourcesFilter,
          resourceDetails.getResourceDirectories(),
          ImmutableSet.copyOf(resourceDetails.getWhitelistedStringDirectories()),
          locales,
          resourceCompressionMode,
          resourceFilter);
      ruleResolver.addToIndex(resourcesFilter);

      filteredResourcesProvider = resourcesFilter;
      enhancedDeps.add(resourcesFilter);
      resourceRules = ImmutableSortedSet.of(resourcesFilter);
    } else {
      filteredResourcesProvider = new IdentityResourcesProvider(
          resourceDetails.getResourceDirectories().stream()
              .map(pathResolver::getRelativePath)
              .collect(MoreCollectors.toImmutableList()));
    }

    AaptOutputInfo aaptOutputInfo;
    switch (aaptMode) {
      case AAPT1: {
        // Create the AaptPackageResourcesBuildable.
        BuildRuleParams paramsForAaptPackageResources = buildRuleParams
            .withAppendedFlavor(AAPT_PACKAGE_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(ImmutableSortedSet.of()));
        AaptPackageResources aaptPackageResources = new AaptPackageResources(
            paramsForAaptPackageResources,
            ruleFinder,
            ruleResolver,
            manifest,
            filteredResourcesProvider,
            getTargetsAsResourceDeps(resourceDetails.getResourcesWithNonEmptyResDir()),
            getTargetsAsRules(resourceDetails.getResourcesWithEmptyResButNonEmptyAssetsDir()),
            packageableCollection.getAssetsDirectories(),
            resourceUnionPackage,
            shouldBuildStringSourceMap,
            skipCrunchPngs,
            includesVectorDrawables,
            bannedDuplicateResourceTypes,
            manifestEntries);
        ruleResolver.addToIndex(aaptPackageResources);
        enhancedDeps.add(aaptPackageResources);
        aaptOutputInfo = aaptPackageResources.getAaptOutputInfo();
      }
      break;

      case AAPT2: {
        ImmutableList.Builder<Aapt2Compile> compileListBuilder = ImmutableList.builder();
        for (BuildTarget resTarget : resourceDetails.getResourcesWithNonEmptyResDir()) {
          compileListBuilder.add((Aapt2Compile) ruleResolver.requireRule(
              resTarget.withFlavors(AndroidResourceDescription.AAPT2_COMPILE_FLAVOR)));
        }
        ImmutableList<Aapt2Compile> compileList = compileListBuilder.build();
        BuildRuleParams paramsForAapt2Link = buildRuleParams
            .withAppendedFlavor(AAPT2_LINK_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(ImmutableSortedSet.of()));
        Aapt2Link aapt2Link = new Aapt2Link(
            paramsForAapt2Link,
            compileList
        );
        ruleResolver.addToIndex(aapt2Link);
        enhancedDeps.add(aapt2Link);
        aaptOutputInfo = aapt2Link.getAaptOutputInfo();
      }
      break;

      default:
        throw new RuntimeException("Unexpected aaptMode: " + aaptMode);
    }

    Optional<PackageStringAssets> packageStringAssets = Optional.empty();
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      BuildRuleParams paramsForPackageStringAssets = buildRuleParams
          .withAppendedFlavor(PACKAGE_STRING_ASSETS_FLAVOR)
          .copyReplacingDeclaredAndExtraDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(ruleFinder.filterBuildRuleInputs(aaptOutputInfo.getPathToRDotTxt()))
                      .addAll(resourceRules)
                      .addAll(rulesWithResourceDirectories)
                      // Model the dependency on the presence of res directories, which, in the case
                      // of resource filtering, is cached by the `ResourcesFilter` rule.
                      .addAll(
                          Iterables.filter(
                              ImmutableList.of(filteredResourcesProvider),
                              BuildRule.class))
                      .build()),
              Suppliers.ofInstance(ImmutableSortedSet.of()));
      packageStringAssets = Optional.of(
          new PackageStringAssets(
              paramsForPackageStringAssets,
              locales,
              filteredResourcesProvider,
              aaptOutputInfo.getPathToRDotTxt()));
      ruleResolver.addToIndex(packageStringAssets.get());
      enhancedDeps.add(packageStringAssets.get());
    }
    return AndroidBinaryResourcesGraphEnhancementResult.builder()
        .setPackageStringAssets(packageStringAssets)
        .setAaptOutputInfo(aaptOutputInfo)
        .setEnhancedDeps(enhancedDeps.build())
        .build();
  }

  private ImmutableSortedSet<BuildRule> getTargetsAsRules(Collection<BuildTarget> buildTargets) {
    return BuildRules.toBuildRulesFor(
        originalBuildTarget,
        ruleResolver,
        buildTargets);
  }

  private ImmutableList<HasAndroidResourceDeps> getTargetsAsResourceDeps(
      Collection<BuildTarget> targets) {
    return getTargetsAsRules(targets).stream()
        .map(input -> {
          Preconditions.checkState(input instanceof HasAndroidResourceDeps);
          return (HasAndroidResourceDeps) input;
        })
        .collect(MoreCollectors.toImmutableList());
  }
}
