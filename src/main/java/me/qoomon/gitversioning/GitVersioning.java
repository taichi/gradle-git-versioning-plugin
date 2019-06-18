package me.qoomon.gitversioning;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static me.qoomon.gitversioning.StringUtil.substituteText;
import static me.qoomon.gitversioning.StringUtil.valueGroupMap;

public final class GitVersioning {

    public static final String VERSION_DATE_TIME_FORMAT = "yyyyMMdd.HHmmss";
    public static final String NO_COMMIT_DATE = "00000000.000000";

    private GitVersioning() {
    }

    @Nonnull
    public static GitVersionDetails determineVersion(
            final GitRepoSituation repoSituation,
            final VersionDescription commitVersionDescription,
            final List<VersionDescription> branchVersionDescriptions,
            final List<VersionDescription> tagVersionDescriptions,
            final String currentVersion) {

        requireNonNull(repoSituation);
        requireNonNull(commitVersionDescription);
        requireNonNull(branchVersionDescriptions);
        requireNonNull(tagVersionDescriptions);

        // default versioning
        String gitRefType = "commit";
        String gitRefName = repoSituation.getHeadCommit();
        VersionDescription versionDescription = commitVersionDescription;

        if (repoSituation.getHeadBranch() != null) {
            // branch versioning
            for (final VersionDescription branchVersionDescription : branchVersionDescriptions) {
                Optional<String> versionBranch = Optional.of(repoSituation.getHeadBranch())
                        .filter(branch -> branch.matches(branchVersionDescription.getPattern()));
                if (versionBranch.isPresent()) {
                    gitRefType = "branch";
                    gitRefName = versionBranch.get();
                    versionDescription = branchVersionDescription;
                    break;
                }
            }
        } else if (!repoSituation.getHeadTags().isEmpty()) {
            // tag versioning
            for (final VersionDescription tagVersionDescription : tagVersionDescriptions) {
                Optional<String> versionTag = repoSituation.getHeadTags().stream()
                        .filter(tag -> tag.matches(tagVersionDescription.getPattern()))
                        .max(comparing(DefaultArtifactVersion::new));
                if (versionTag.isPresent()) {
                    gitRefType = "tag";
                    gitRefName = versionTag.get();
                    versionDescription = tagVersionDescription;
                    break;
                }
            }
        }
        Map<String, String> refFields = valueGroupMap(versionDescription.getPattern(), gitRefName);
        refFields.remove("0");

        Map<String, String> projectVersionDataMap = new HashMap<>();
        projectVersionDataMap.put("version", currentVersion);
        projectVersionDataMap.put("version.release", currentVersion.replaceFirst("-SNAPSHOT$",""));
        projectVersionDataMap.put("commit", repoSituation.getHeadCommit());
        projectVersionDataMap.put("commit.short", repoSituation.getHeadCommit().substring(0, 7));
        projectVersionDataMap.put("commit.timestamp", Long.toString(repoSituation.getHeadCommitTimestamp()));
        projectVersionDataMap.put("commit.timestamp.datetime", formatHeadCommitTimestamp(repoSituation.getHeadCommitTimestamp()));
        projectVersionDataMap.put("ref", gitRefName);
        projectVersionDataMap.put(gitRefType, gitRefName);
        projectVersionDataMap.putAll(refFields);

        String gitVersion = substituteText(versionDescription.getVersionFormat(), projectVersionDataMap)
                .replace("/", "-");

        return new GitVersionDetails(
                repoSituation.isClean(),
                repoSituation.getHeadCommit(),
                gitRefType,
                gitRefName,
                refFields,
                gitVersion
        );
    }

    private static String formatHeadCommitTimestamp(long headCommitDate){
        if (headCommitDate == 0){
            return NO_COMMIT_DATE;
        }
        return DateTimeFormatter
                .ofPattern(VERSION_DATE_TIME_FORMAT)
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(headCommitDate));
    }

}
