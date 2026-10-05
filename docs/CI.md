# Backend continuous integration and container publication

Updated: 2026-10-05.

## Workflow and checks

`.github/workflows/ci.yml` runs on every pull request and push to `main`. It uses GitHub-hosted Ubuntu 24.04 runners; no runner or SSH credential is installed on the application VM. Every action is pinned to its full upstream commit, with a release comment for maintenance.

The `verify` job installs Temurin Java 21, caches Maven downloads, checks Java formatting and runs the complete Maven verification/package lifecycle. It starts a disposable MariaDB 11.8.8 service and waits for its own database healthcheck before running tests. The service uses database/user `bmepilots_test` on port 3308, matching the integration-test properties. `TEST_DB_PASSWORD` is the nonsecret fixture password for this ephemeral CI database. Neither the persistent development database nor a deployed database is contacted. Gmail remains disabled.

The commands are:

```bash
bash ./mvnw -B --no-transfer-progress spotless:check
TEST_DB_PASSWORD=ci-test-password bash ./mvnw -B --no-transfer-progress verify
```

The fixture environment is only valid with the workflow's disposable database. To run locally, follow `TESTING.md`. The wrapper is invoked through Bash because its current Git file mode is `100644`; a fresh Linux checkout does not mark it executable.

On pull requests, the same read-only job also builds the Dockerfile for `linux/amd64` without publishing an image. On `main`, the separate `publish` job depends on successful verification and builds the verified commit. A failed verification prevents publication. Each job has a 30-minute timeout. Newer commits cancel older runs on the same pull request; `main` runs are serialized so an older build cannot finish after a newer one and move the branch tag backwards. GitHub may replace an older pending run with a newer pending run.

## GHCR identity and permissions

The publication job alone has `packages: write`. It logs into `ghcr.io` using the automatically issued, short-lived `GITHUB_TOKEN`, then pushes these tags:

```text
ghcr.io/bmepilots/backend:<full-40-character-source-commit>
ghcr.io/bmepilots/backend:main
```

The actual image name is derived from the lowercase GitHub repository identity, so a fork uses its own namespace. The image includes OCI source/revision labels connecting it to the repository and commit. Checkouts do not persist their token in Git configuration. Pull requests never enter the publication job and receive no registry login credentials.

GitHub Actions must be enabled, and organization/package policy must permit this repository to publish packages. No custom PAT is required for CI. For an existing package created outside this workflow, grant this repository Actions access in the package settings if publication reports a permission error. Keep the package private for this private portal. Pulling a private package from the VM is a separate operation requiring a read-only package credential; never copy the workflow token to the VM. GitHub documents the registry/token setup in [Publishing Docker images](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images).

## Deployment references and boundaries

The full commit tag identifies the source revision; `main` is a moving convenience pointer. Registry tags can be reassigned, including on a rerun, so a tag alone is not an immutable artifact guarantee. The publication step writes the exact `ghcr.io/bmepilots/backend@sha256:...` reference to the workflow summary. Record that digest in the deployment release manifest for repeatable rollout/rollback. Retain the corresponding registry versions; deleting an old version makes it unavailable for rollback.

The workflow builds and publishes only. It does not SSH to the VM, update Compose, migrate a deployed database or change Cloudflare. The server updater must select compatible backend/frontend digests, back up persistent data, wait for healthchecks, and handle failures separately. Flyway migrations still belong exclusively to this repository and are applied by backend startup; rolling back an image does not reverse a database migration.

The runtime image label `io.bmepilots.api.contracts="1,2"` declares support for both the original multipart API (contract 1) and staged document uploads (contract 2). The frontend declares its required contract in its image. Deployment must check this pair before rollout, so an earlier-finishing frontend workflow cannot put a new client on an incompatible old backend. Unlabeled historical images support contract 1 only. Update this label deliberately when adding/removing a client contract and coordinate the sibling frontend and updater; workflow success alone does not establish cross-repository compatibility.

## Maintenance and verification

On 2026-10-05 both repository workflows passed actionlint 1.7.12, including its ShellCheck validation. The backend service database, username, port and password variable were checked against all three MariaDB integration-test classes. This local validation was followed by a successful GitHub verification/publication run on 2026-10-05; see STATUS.md for the run link.

- When upgrading Java, update both CI and the Dockerfile and rerun the full MariaDB integration suite.
- When changing the MariaDB version, coordinate the image with `../db` and test it before rollout.
- Update pinned action commits from their official release tags and keep the version comments accurate. Current pins were resolved against the official GitHub repositories on 2026-10-05.
- BuildKit uses a GitHub Actions cache scoped to `backend`; it is a performance cache, not a release artifact or deployment input.
- Do not add runtime `.env`, passwords, Gmail credentials, uploaded files, dumps or application logs to build contexts, workflow artifacts or summaries.
- Workflow linting and local checks do not prove a hosted run or registry publication succeeded. Record actual run URLs/digests in `STATUS.md` only after GitHub has executed them.
