package me.qoomon.gradle.gitversioning;

import groovy.lang.Closure;
import me.qoomon.gitversioning.commons.GitDescription;
import me.qoomon.gitversioning.commons.GitSituation;
import me.qoomon.gitversioning.commons.Lazy;
import me.qoomon.gradle.gitversioning.GitVersioningPluginConfig.PropertyDescription;
import me.qoomon.gradle.gitversioning.GitVersioningPluginConfig.VersionDescription;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.ExtraPropertiesExtension;

import java.io.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static java.lang.Boolean.parseBoolean;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static me.qoomon.gitversioning.commons.GitRefType.*;
import static me.qoomon.gitversioning.commons.StringUtil.*;
import static org.apache.commons.lang3.StringUtils.leftPad;
import static org.gradle.util.ConfigureUtil.configure;

public class GitVersioningPluginExtension {

    private static final String OPTION_NAME_GIT_TAG = "git.tag";
    private static final String OPTION_NAME_GIT_BRANCH = "git.branch";
    private static final String OPTION_NAME_DISABLE = "versioning.disable";
    private static final String OPTION_PREFER_TAGS = "versioning.preferTags";
    private static final String OPTION_UPDATE_GRADLE_PROPERTIES = "versioning.updateGradleProperties";

    private static final String DEFAULT_BRANCH_VERSION_FORMAT = "${branch}-SNAPSHOT";
    private static final String DEFAULT_TAG_VERSION_FORMAT = "${tag}";
    private static final String DEFAULT_COMMIT_VERSION_FORMAT = "${commit}";

    public final Project rootProject;
    public final Logger logger; // TODO logging is not working

    public GitVersionDetails gitVersionDetails;
    public Map<String, PropertyDescription> propertyDescriptionMap;
    public Map<String, Supplier<String>> globalFormatPlaceholderMap;
    public Map<String, String> gitProjectProperties;

    public GitVersioningPluginExtension(Project project) {
        this.rootProject = project;
        this.logger = rootProject.getLogger();
    }

    public void apply(Closure<?> closure) throws IOException {
        GitVersioningPluginConfig config = new GitVersioningPluginConfig();
        configure(closure, config);
        apply(config);
    }

    public void apply(GitVersioningPluginConfig config) throws IOException {
        setDefaults(config);

        // check if extension is disabled by command option
        String commandOptionDisable = getCommandOption(OPTION_NAME_DISABLE);
        if (commandOptionDisable != null) {
            boolean disabled = parseBoolean(commandOptionDisable);
            if (disabled) {
                logger.info("skip - versioning is disabled by command option");
                return;
            }
        } else {
            // check if extension is disabled by config option
            if (config.disable) {
                logger.info("skip - versioning is disabled by config option");
                return;
            }
        }

        GitSituation gitSituation = getGitSituation(rootProject.getProjectDir());
        if (gitSituation == null) {
            logger.warn("skip - project is not part of a git repository");
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("git situation:");
            logger.debug("  root directory: " + gitSituation.getRootDirectory());
            logger.debug("  head commit: " + gitSituation.getHash());
            logger.debug("  head commit timestamp: " + gitSituation.getTimestamp());
            logger.debug("  head branch: " + gitSituation.getBranch());
            logger.debug("  head tags: " + gitSituation.getTags());
            logger.debug("  head description: " + gitSituation.getDescription());
        }

        boolean preferTagsOption = getPreferTagsOption(config);
        logger.debug("option - prefer tags: " + preferTagsOption);

        // determine git version details
        gitVersionDetails = getGitVersionDetails(gitSituation, config, preferTagsOption);
        logger.info("git ref: " + gitVersionDetails.getRefName()
                + " (" + gitVersionDetails.getRefType().name().toLowerCase() + ")");
        propertyDescriptionMap = gitVersionDetails.getConfig().properties.stream()
                .collect(toMap(property -> property.name, property -> property));

        boolean updateGradlePropertiesFileOption = getUpdateGradlePropertiesFileOption(config, gitVersionDetails.getConfig());
        logger.debug("option: update gradle.properties file: " + updateGradlePropertiesFileOption);

        Pattern describeTagPattern = getDescribeTagPattern(config, gitVersionDetails.getConfig());
        logger.debug("option: git describe: " + describeTagPattern);
        gitSituation.setDescribeTagPattern(describeTagPattern);

        globalFormatPlaceholderMap = generateGlobalFormatPlaceholderMap(gitSituation, gitVersionDetails, rootProject);
        gitProjectProperties = generateGitProjectProperties(gitSituation, gitVersionDetails);

        rootProject.getAllprojects().forEach(project -> {
            String originalProjectVersion = project.getVersion().toString();

            updateVersion(project);
            updatePropertyValues(project, originalProjectVersion);

            addGitProperties(project);
            if (updateGradlePropertiesFileOption) {
                File gradleProperties = project.file("gradle.properties");
                if (gradleProperties.exists()) {
                    updateGradlePropertiesFile(gradleProperties, project);
                }
            }
        });
    }


    // ---- project processing -----------------------------------------------------------------------------------------

    private void updateVersion(Project project) {
        String gitProjectVersion = getGitVersion(project.getVersion().toString());
        project.getLogger().info("update version: " + gitProjectVersion);
        project.setVersion(gitProjectVersion);
    }

    private void updatePropertyValues(Project project, String originalProjectVersion) {
        // properties section
        project.getProperties().forEach((key, value) -> {
            if (value instanceof String) {
                String gitPropertyValue = getGitProjectPropertyValue(key, (String) value, originalProjectVersion);
                if (!gitPropertyValue.equals(value)) {
                    project.getLogger().info("update property " + key + ": " + gitPropertyValue);
                    project.setProperty(key, gitPropertyValue);
                }
            }
        });
    }

    private void addGitProperties(Project project) {
        ExtraPropertiesExtension extraProperties = project.getExtensions().getExtraProperties();
        gitProjectProperties.forEach(extraProperties::set);
    }

    private void updateGradlePropertiesFile(File gradleProperties, Project project) {
        PropertiesConfiguration gradlePropertiesConfig = new PropertiesConfiguration();
        try (FileReader reader = new FileReader(gradleProperties)) {
            gradlePropertiesConfig.read(reader);
        } catch (IOException | ConfigurationException e) {
            throw new RuntimeException(e);
        }

        // handle version
        if (gradlePropertiesConfig.containsKey("version")) {
            Object gradlePropertyVersion = gradlePropertiesConfig.getProperty("version");
            Object projectVersion = project.getVersion();
            if (!Objects.equals(projectVersion, gradlePropertyVersion)) {
                gradlePropertiesConfig.setProperty("version", projectVersion);
            }
        }

        // handle properties
        Map<String, ?> projectProperties = project.getProperties();
        gitVersionDetails.getConfig().properties.forEach(property -> {
            String propertyName = property.name;
            if (gradlePropertiesConfig.containsKey(propertyName)) {
                Object gradlePropertyValue = gradlePropertiesConfig.getProperty(propertyName);
                Object projectPropertyValue = projectProperties.get(propertyName);
                if (!Objects.equals(projectPropertyValue, gradlePropertyValue)) {
                    gradlePropertiesConfig.setProperty(propertyName, projectPropertyValue);
                }
            }
        });

        try (FileWriter writer = new FileWriter(gradleProperties)) {
            gradlePropertiesConfig.write(writer);
        } catch (IOException | ConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- versioning -------------------------------------------------------------------------------------------------
    private GitSituation getGitSituation(File executionRootDirectory) throws IOException {
        final FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder().findGitDir(executionRootDirectory);
        if (repositoryBuilder.getGitDir() == null) {
            return null;
        }

        final Repository repository = repositoryBuilder.build();
        final GitSituation gitSituation = new GitSituation(repository);

        String providedTag = getCommandOption(OPTION_NAME_GIT_TAG);
        if (providedTag != null) {
            logger.debug("set git head tag by command option: " + providedTag);
            gitSituation.setBranch(null);
            gitSituation.setTags(providedTag.isEmpty() ? emptyList() : singletonList(providedTag));
        }
        String providedBranch = getCommandOption(OPTION_NAME_GIT_BRANCH);
        if (providedBranch != null) {
            logger.debug("set git head branch by command option: " + providedBranch);
            gitSituation.setBranch(providedBranch);
        }

        return gitSituation;
    }

    private static GitVersionDetails getGitVersionDetails(GitSituation gitSituation, GitVersioningPluginConfig config, boolean preferTags) {
        String headCommit = gitSituation.getHash();

        // detached tag
        if (!gitSituation.getTags().isEmpty() && (gitSituation.isDetached() || preferTags)) {
            // sort tags by maven version logic
            List<String> sortedHeadTags = gitSituation.getTags().stream()
                    .sorted(comparing(DefaultArtifactVersion::new)).collect(toList());
            for (VersionDescription tagConfig : config.tags) {
                for (String headTag : sortedHeadTags) {
                    if (tagConfig.pattern == null || headTag.matches(tagConfig.pattern)) {
                        return new GitVersionDetails(headCommit, TAG, headTag, tagConfig);
                    }
                }
            }
        }

        // detached commit
        if (gitSituation.isDetached()) {
            if (config.commit != null) {
                if (config.commit.pattern == null || headCommit.matches(config.commit.pattern)) {
                    return new GitVersionDetails(headCommit, COMMIT, headCommit, config.commit);
                }
            }

            // default config for detached head commit
            return new GitVersionDetails(headCommit, COMMIT, headCommit, new VersionDescription() {{
                versionFormat = DEFAULT_COMMIT_VERSION_FORMAT;
            }});
        }

        // branch
        {
            String headBranch = gitSituation.getBranch();
            for (VersionDescription branchConfig : config.branches) {
                if (branchConfig.pattern == null || headBranch.matches(branchConfig.pattern)) {
                    return new GitVersionDetails(headCommit, BRANCH, headBranch, branchConfig);
                }
            }

            // default config for branch
            return new GitVersionDetails(headCommit, BRANCH, headBranch, new VersionDescription() {{
                versionFormat = DEFAULT_BRANCH_VERSION_FORMAT;
            }});
        }
    }

    private String getGitVersion(String originalProjectVersion) {
        final Map<String, Supplier<String>> placeholderMap = generateFormatPlaceholderMap(originalProjectVersion);
        return substituteText(gitVersionDetails.getConfig().versionFormat, placeholderMap)
                // replace invalid version characters
                .replace("/", "-");
    }

    private String getGitProjectPropertyValue(String key, String originalValue, String originalProjectVersion) {
        PropertyDescription propertyConfig = propertyDescriptionMap.get(key);
        if (propertyConfig == null) {
            return originalValue;
        }
        final Map<String, Supplier<String>> placeholderMap = generateFormatPlaceholderMap(originalProjectVersion);
        placeholderMap.put("value", () -> originalValue);
        return substituteText(propertyConfig.valueFormat, placeholderMap);
    }

    private Map<String, Supplier<String>> generateFormatPlaceholderMap(String originalProjectVersion) {
        final Map<String, Supplier<String>> placeholderMap = new HashMap<>(globalFormatPlaceholderMap);
        placeholderMap.put("version", () -> originalProjectVersion);
        placeholderMap.put("version.release", Lazy.by(
                () -> originalProjectVersion.replaceFirst("-SNAPSHOT$", "")));
        return placeholderMap;
    }

    private Map<String, Supplier<String>> generateGlobalFormatPlaceholderMap(GitSituation gitSituation, GitVersionDetails gitVersionDetails, Project rootProject) {

        final Map<String, Supplier<String>> placeholderMap = new HashMap<>();

        final Lazy<String> hash = Lazy.by(gitSituation::getHash);
        placeholderMap.put("commit", hash);
        placeholderMap.put("commit.short", Lazy.by(() -> hash.get().substring(0, 7)));

        final Lazy<ZonedDateTime> headCommitDateTime = Lazy.by(gitSituation::getTimestamp);
        placeholderMap.put("commit.timestamp", Lazy.by(() -> String.valueOf(headCommitDateTime.get().toEpochSecond())));
        placeholderMap.put("commit.timestamp.year", Lazy.by(() -> String.valueOf(headCommitDateTime.get().getYear())));
        placeholderMap.put("commit.timestamp.month", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMonthValue()), 2, "0")));
        placeholderMap.put("commit.timestamp.day", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getDayOfMonth()), 2, "0")));
        placeholderMap.put("commit.timestamp.hour", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getHour()), 2, "0")));
        placeholderMap.put("commit.timestamp.minute", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMinute()), 2, "0")));
        placeholderMap.put("commit.timestamp.second", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getSecond()), 2, "0")));
        placeholderMap.put("commit.timestamp.datetime", Lazy.by(() -> headCommitDateTime.get().toEpochSecond() > 0
                ? headCommitDateTime.get().format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")) : "00000000.000000"));

        final String refName = gitVersionDetails.getRefName();
        final Lazy<String> refNameSlug = Lazy.by(() -> slugify(refName));
        placeholderMap.put("ref", () -> refName);
        placeholderMap.put("ref.slug", refNameSlug);
        final String refTypeName = gitVersionDetails.getRefType().name().toLowerCase();
        placeholderMap.put(refTypeName, () ->refName);
        placeholderMap.put(refTypeName + ".slug", refNameSlug);

        // ref pattern groups
        final String refPattern = gitVersionDetails.getConfig().pattern;
        if (refPattern != null) {
            for (Map.Entry<String, String> patternGroup : patternGroupValues(refName, refPattern).entrySet()) {
                final String groupName = patternGroup.getKey();
                final String value = patternGroup.getValue() != null ? patternGroup.getValue() : "";
                placeholderMap.put(groupName, () -> value);
                placeholderMap.put(groupName + ".slug", Lazy.by(() -> slugify(value)));
            }
        }

        // dirty
        final Lazy<Boolean> dirty = Lazy.by(() -> !gitSituation.isClean());
        placeholderMap.put("dirty", Lazy.by(() -> dirty.get() ? "-DIRTY" : ""));
        placeholderMap.put("dirty.snapshot", Lazy.by(() -> dirty.get() ? "-SNAPSHOT" : ""));

        // describe
        final Lazy<GitDescription> description = Lazy.by(gitSituation::getDescription);
        placeholderMap.put("describe", Lazy.by(() -> description.get().toString()));
        final Lazy<String> descriptionTag = Lazy.by(() -> description.get().getTag());
        placeholderMap.put("describe.tag", descriptionTag);
        placeholderMap.put("describe.distance", Lazy.by(() -> String.valueOf(description.get().getDistance())));

        // describe tag pattern groups
        final Lazy<Map<String, String>> describeTagPatternValues = Lazy.by(
                () -> patternGroupValues(gitSituation.getDescribeTagPattern(), descriptionTag.get()));
        for (String groupName : patternGroups(gitSituation.getDescribeTagPattern())) {
            Lazy<String> value = Lazy.by(() -> describeTagPatternValues.get().get(groupName));
            placeholderMap.put("describe." + groupName, value);
            placeholderMap.put("describe." + groupName + ".slug", Lazy.by(() -> slugify(value.get())));
        }

        // command parameters e.g. mvn -Dfoo=123 will be available as ${foo}
        rootProject.getProperties().forEach((key, value) -> {
            if (value instanceof String) {
                placeholderMap.put(key, () -> (String) value);
            }
        });

        // environment variables e.g. BUILD_NUMBER=123 will be available as ${env.BUILD_NUMBER}
        System.getenv().forEach((key, value) -> placeholderMap.put("env." + key, () -> value));

        return placeholderMap;
    }

    private static Map<String, String> generateGitProjectProperties(GitSituation gitSituation, GitVersionDetails gitVersionDetails) {
        Map<String, String> properties = new HashMap<>();

        properties.put("git.commit", gitVersionDetails.getCommit());

        ZonedDateTime headCommitDateTime = gitSituation.getTimestamp();
        properties.put("git.commit.timestamp", String.valueOf(headCommitDateTime.toEpochSecond()));
        properties.put("git.commit.timestamp.datetime", headCommitDateTime.toEpochSecond() > 0
                ? headCommitDateTime.format(ISO_INSTANT) : "0000-00-00T00:00:00Z");

        String refTypeName = gitVersionDetails.getRefType().name().toLowerCase();
        String refName = gitVersionDetails.getRefName();
        String refNameSlug = slugify(refName);
        properties.put("git.ref", refName);
        properties.put("git.ref.slug", refNameSlug);
        properties.put("git." + refTypeName, refName);
        properties.put("git." + refTypeName + ".slug", refNameSlug);

        properties.put("git.dirty", Boolean.toString(!gitSituation.isClean()));

        return properties;
    }


    // ---- configuration ----------------------------------------------------------------------------------------------

    private void setDefaults(GitVersioningPluginConfig config) {
        for (VersionDescription versionDescription : config.branches) {
            if (versionDescription.versionFormat == null) {
                versionDescription.versionFormat = DEFAULT_BRANCH_VERSION_FORMAT;
            }
        }
        for (VersionDescription versionDescription : config.tags) {
            if (versionDescription.versionFormat == null) {
                versionDescription.versionFormat = DEFAULT_TAG_VERSION_FORMAT;
            }
        }
        if (config.commit != null) {
            if (config.commit.versionFormat == null) {
                config.commit.versionFormat = DEFAULT_COMMIT_VERSION_FORMAT;
            }
        }
    }

    private String getCommandOption(final String name) {
        String value = (String) rootProject.getProperties().get(name);
        if (value == null) {
            String plainName = name.replaceFirst("^versioning\\.", "");
            String environmentVariableName = "VERSIONING_"
                    + String.join("_", plainName.split("(?=\\p{Lu})"))
                    .replaceAll("\\.", "_")
                    .toUpperCase();
            value = System.getenv(environmentVariableName);
        }
        if (value == null) {
            value = System.getProperty(name);
        }
        return value;
    }

    private boolean getPreferTagsOption(GitVersioningPluginConfig config) {
        final boolean preferTagsOption;
        final String preferTagsCommandOption = getCommandOption(OPTION_PREFER_TAGS);
        if (preferTagsCommandOption != null) {
            preferTagsOption = parseBoolean(preferTagsCommandOption);
        } else {
            preferTagsOption = config.preferTags;
        }
        return preferTagsOption;
    }

    private boolean getUpdateGradlePropertiesFileOption(final GitVersioningPluginConfig config, final VersionDescription gitRefConfig) {
        final String updatePomCommandOption = getCommandOption(OPTION_UPDATE_GRADLE_PROPERTIES);
        if (updatePomCommandOption != null) {
            return parseBoolean(updatePomCommandOption);
        }

        if (gitRefConfig.updateGradleProperties != null) {
            return gitRefConfig.updateGradleProperties;
        }

        if (config.updateGradleProperties != null) {
            return config.updateGradleProperties;
        }

        return false;
    }

    private Pattern getDescribeTagPattern(final GitVersioningPluginConfig config, final VersionDescription gitRefConfig) {

        if (gitRefConfig.describeTagPattern != null) {
            return Pattern.compile(gitRefConfig.describeTagPattern);
        }

        if (config.describeTagPattern != null) {
            return Pattern.compile(config.describeTagPattern);
        }

        return Pattern.compile(".*");
    }

    // ---- misc -------------------------------------------------------------------------------------------------------

    private static String slugify(String value) {
        return value
                .replace("/", "-")
                .toLowerCase();
    }
}
