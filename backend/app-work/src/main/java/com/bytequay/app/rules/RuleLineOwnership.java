/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.rules;

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.Workspace;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Reads attribution for removed or replaced lines at the PR's merge base, without checking out or modifying user files. */
@Component
public class RuleLineOwnership
{
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+\\d+(?:,\\d+)? @@.*");
    private static final Pattern BLAME = Pattern.compile("^[0-9a-f]{40,64} \\d+ (\\d+)(?: \\d+)?$");
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAXIMUM_OUTPUT = 2 * 1024 * 1024;

    private final GitCommandRunner git;
    private final WorkspacePaths paths;
    private final CredentialService credentials;
    private final PullRequestService reads;

    public RuleLineOwnership(GitCommandRunner git, WorkspacePaths paths, CredentialService credentials, PullRequestService reads)
    {
        this.git = requireNonNull(git, "git is null");
        this.paths = requireNonNull(paths, "paths is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.reads = requireNonNull(reads, "reads is null");
    }

    public boolean touches(Workspace workspace, PullRequestRead route, String head, String baseBranch, List<DiffFile> files)
    {
        Path repository = paths.repository(workspace);
        checkState(Files.isDirectory(repository), "The watched repository is not available locally");
        checkState(head.matches("[0-9a-f]{40,64}"), "The pull request head is unknown");
        checkState(baseBranch != null && git.run(repository, "check-ref-format", "refs/heads/" + baseBranch).successful(), "The base branch is invalid");
        String prefix = "refs/bytequay/rules/" + route.number();
        String url = route.repository().httpCloneUrl().toString();
        GitCommandRunner.Result fetched = git.run(repository, Duration.ofMinutes(2), credentials.gitAuthenticationEnvironment(workspace.id(), ImmutableList.of(url)),
                ImmutableList.of("fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", url,
                        "+" + reads.requestHeadRef(route) + ":" + prefix + "/head", "+refs/heads/" + baseBranch + ":" + prefix + "/base"));
        checkState(fetched.successful() && !fetched.truncated(), "Could not read the pull request's commits");
        checkState(read(repository, "rev-parse", prefix + "/head").strip().equals(head), "The pull request changed while checking line ownership");
        String base = read(repository, "merge-base", prefix + "/base", head).strip();
        GitCommandRunner.Result email = git.runLocalBoundedStdout(repository, READ_TIMEOUT, 4096, ImmutableList.of("config", "--get", "user.email"));
        String ownEmail = email.successful() && !email.truncated() ? email.output().strip().toLowerCase(Locale.ROOT) : "";
        checkState(files.size() <= 100, "The change is too large to check line ownership automatically");
        for (DiffFile file : files) {
            Set<Integer> changed = removedLines(file.patch());
            if (changed.isEmpty() || "renamed".equals(file.status())) {
                continue;
            }
            String blame = read(repository, "blame", "--line-porcelain", "--no-textconv", "--encoding=UTF-8", base, "--", file.filename());
            if (attributed(blame, changed, ownEmail, route.login())) {
                return true;
            }
        }
        return false;
    }

    private String read(Path repository, String... arguments)
    {
        GitCommandRunner.Result result = git.runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, ImmutableList.copyOf(arguments));
        checkState(result.successful() && !result.truncated(), "Line ownership could not be read completely");
        return result.output();
    }

    static Set<Integer> removedLines(String patch)
    {
        Set<Integer> changed = new HashSet<>();
        if (patch == null) {
            return changed;
        }
        int oldLine = -1;
        for (String line : patch.lines().toList()) {
            Matcher hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                oldLine = Integer.parseInt(hunk.group(1));
            }
            else if (oldLine >= 0 && line.startsWith("-")) {
                changed.add(oldLine++);
            }
            else if (oldLine >= 0 && line.startsWith(" ")) {
                oldLine++;
            }
        }
        return changed;
    }

    static boolean attributed(String blame, Set<Integer> changed, String ownEmail, String login)
    {
        int line = -1;
        for (String value : blame.lines().toList()) {
            Matcher header = BLAME.matcher(value);
            if (header.matches()) {
                line = Integer.parseInt(header.group(1));
            }
            else if (changed.contains(line) && value.startsWith("author-mail <") && value.endsWith(">")) {
                String email = value.substring("author-mail <".length(), value.length() - 1).toLowerCase(Locale.ROOT);
                String user = login.toLowerCase(Locale.ROOT);
                if (!ownEmail.isEmpty() && email.equals(ownEmail) || email.equals(user + "@users.noreply.github.com")
                        || email.matches("[0-9]+\\+" + Pattern.quote(user) + "@users\\.noreply\\.github\\.com")) {
                    return true;
                }
            }
        }
        return false;
    }
}
