# OpenCDMP Fedora deposit service

**repository-deposit-fedora** is an [OpenCDMP](https://github.com/OpenCDMP/OpenCDMP) deposit service that publishes plans to a [Fedora](https://fedorarepository.org/) repository. It creates a Fedora resource with the plan's metadata, as Dublin Core RDF, and its files, and returns the resource's path under the repository URL, which OpenCDMP stores with the plan as its identifier. Fedora does not mint DOIs.

The service implements the [repository-deposit-base](https://github.com/OpenCDMP/repository-deposit-base) contract. See [Deposit services](https://opencdmp.github.io/optional-services/deposit-services/) in the OpenCDMP documentation.

## Features

- Deposits a plan as a new Fedora resource, or as a new version of a resource deposited earlier
- Publishes under the organisation's account or the user's own account, with a Fedora username and password
- Uploads the plan document (PDF), the machine-actionable plan (RDA JSON) and the supporting files (ZIP)
- Lets users review and edit the resource's metadata before it is published
- Fills Dublin Core elements from the answers of the plan blueprint and asset templates, through [semantics](https://opencdmp.github.io/optional-services/deposit-services/fedora-semantics/)

## Requirements

- Fedora 6.x, and a Fedora account, with a username and password, that may create resources in the target container
- An OpenCDMP 2.0 installation, and its Keycloak realm with the `plugins` client scope
- To build from source: Java 21 and Maven. The service is a Spring Boot 4.0 application built on `repository-deposit-base`; the exact versions are in [`pom.xml`](pom.xml).

## Run with Docker

The image is [`opencdmp/repository-deposit-fedora`](https://hub.docker.com/r/opencdmp/repository-deposit-fedora) on Docker Hub. It listens on port `8080`, reads its settings from `/config/app-<PROFILE>.env` and writes its logs to `/logs`:

```bash
docker run -d --name opencdmp-fedora \
  --network <opencdmp-network> \
  -e PROFILE=production \
  -v /srv/opencdmp/fedora/config:/config \
  -v /srv/opencdmp/fedora/logs:/logs \
  opencdmp/repository-deposit-fedora:<tag>
```

Use the tag that the [changelog](https://opencdmp.github.io/deployment/install/changelog/) lists for your OpenCDMP release. Keep the port on the internal network: the `/actuator` endpoints are served without authentication. For a Docker Compose entry, see [Registering plugin services](https://opencdmp.github.io/deployment/configuration/plugin-services/#step-1-run-the-service).

## Build from source

```bash
mvn clean package
docker build -t repository-deposit-fedora:local .
```

Maven builds `web/target/repository-deposit-web-1.0.0-SNAPSHOT.jar`, and the `Dockerfile` builds the service from source on its own. The service contacts Keycloak at `IDP_ISSUER_URI` when it starts, and does not start if it cannot reach it. To run the JAR against a local OpenCDMP installation, see [Run and test a plugin locally](https://opencdmp.github.io/developers/plugins/run-locally/).

## Configuration

The service reads its settings from environment variables or from `app-<PROFILE>.env`. The main ones:

| Variable | Description |
| --- | --- |
| `IDP_ISSUER_URI` | Keycloak realm URL, the same value as in the OpenCDMP API service |
| `IDP_CLAIMS_ROLES_PATH` | `plugins.roles` |
| `SECURITY_AUDIENCE` | `plugins` |
| `OPENCDMP_DOMAIN` | Address of the OpenCDMP installation, with a trailing slash |
| `DEPOSIT_FEDORA_URL` | Fedora REST API URL, such as `https://fedora.example.org/fcrepo/rest/` |
| `DEPOSIT_FEDORA_RECORD_URL` | Address of a deposited resource, with `{doi_id}` in place of its path |
| `DEPOSIT_FEDORA_USERNAME`, `DEPOSIT_FEDORA_PASSWORD` | Username and password of the installation's Fedora account |
| `DEPOSIT_FEDORA_COLLECTION_ID` | Existing container, under the repository URL, that new resources are created in; empty for the repository root |
| `DEPOSIT_FEDORA_SUPPORTS_PREVIEW` | Metadata review before publishing (default `true`) |
| `STORAGE_PATH` | Folder of temporary and shared files |

All variables, with defaults and examples, are in [Deposit services: Fedora](https://opencdmp.github.io/deployment/configuration/backend/deposit/#fedora); the authentication, logging and storage settings are in [Settings common to all plugin services](https://opencdmp.github.io/deployment/configuration/plugin-services/#settings-common-to-all-plugin-services). Each organisation can override the account and the record defaults in its [organisation settings](https://opencdmp.github.io/optional-services/deposit-services/#organisation-settings).

### Publishing accounts

For each deposit, the service signs in to Fedora with the first of these that is set:

1. The username and password the user saved in their [profile](https://opencdmp.github.io/using/profile-settings/#repository-accounts) or typed in the publish dialog (`authInfo.authFields`, codes `fedora-username` and `fedora-password`)
2. The organisation's **Username** and **Password** settings
3. `DEPOSIT_FEDORA_USERNAME` and `DEPOSIT_FEDORA_PASSWORD`

OpenCDMP decides which accounts a user may publish under, and never falls back to the organisation's or the installation's account when a user chose their own. See [Publishing accounts and files](https://opencdmp.github.io/optional-services/deposit-services/#publishing-accounts-and-files).

## Register with OpenCDMP

Register the service for the whole installation in the configuration of the API service, or for one organisation under **Settings > Plugins > Add plugin**. Use the plugin id `fedora`, and register a PDF and an RDA JSON transformer with it. See [Registering plugin services](https://opencdmp.github.io/deployment/configuration/plugin-services/) and [Organisation plugins](https://opencdmp.github.io/administering/tenant-management/plugins/).

## API

All endpoints are under `/api/deposit`. They accept only a Bearer token from the installation's Keycloak realm that carries the `plugins` audience; the OpenCDMP API service obtains one for each call. See [Let the plugin accept the API service's tokens](https://opencdmp.github.io/deployment/configuration/plugin-services/#plugin-tokens).

| Endpoint | Description |
| --- | --- |
| `POST /api/deposit` | Deposit a plan and return the resource's path |
| `POST /api/deposit/preview` | Return the metadata that would be deposited, as an editable form |
| `GET /api/deposit/configuration` | Return the service's `DepositConfiguration` |
| `GET /api/deposit/logo` | Return the Fedora logo, Base64-encoded |

A deposit takes a `PlanDepositModel`. For a new version of a resource deposited earlier, `previousDOI` holds the resource's path. The service then saves the resource's current state as a Fedora version, replaces its metadata and files, and returns the same path:

```json
{
  "planModel": {
    "id": "<plan-id>",
    "label": "My research plan",
    "previousDOI": "<path-of-the-earlier-resource>"
  },
  "authInfo": {
    "authFields": [
      { "code": "fedora-username", "textValue": "<user-username>" },
      { "code": "fedora-password", "textValue": "<user-password>" }
    ]
  }
}
```

The models are described in the [Deposit plugin guide](https://opencdmp.github.io/developers/plugins/deposit/#key-interfaces-and-classes) and [Common models](https://opencdmp.github.io/developers/plugins/common-models/).

## Deposit preview

The service reports `supportsPreview: true` in its configuration unless `DEPOSIT_FEDORA_SUPPORTS_PREVIEW` is `false`. OpenCDMP then shows users the resource's metadata in a form before anything is submitted to Fedora:

- `POST /api/deposit/preview` takes the same `PlanDepositModel` as a deposit, runs the same mapping and returns the form, with the mapped values filled in.
- `POST /api/deposit` applies the user's edits before it writes the resource or its new version.

The contract is described in [Deposit preview and metadata review](https://opencdmp.github.io/developers/plugins/deposit-preview/).

### The form

| Group code | Contents |
|---|---|
| `files` | `files` (read-only): the names of the files the deposit will upload, when the platform provides them |
| `basic-information` | One field for each of the 15 Dublin Core elements, from `title` (required) to `rights`. An element with several values is shown as tags. |

An organisation can hide groups and lock record defaults in its settings.

## Related resources

- [Deposit services overview](https://opencdmp.github.io/optional-services/deposit-services/)
- [Fedora semantics reference](https://opencdmp.github.io/optional-services/deposit-services/fedora-semantics/)
- [Deposit services configuration](https://opencdmp.github.io/deployment/configuration/backend/deposit/#fedora)
- [Registering plugin services](https://opencdmp.github.io/deployment/configuration/plugin-services/)
- [Deposit a plan](https://opencdmp.github.io/using/plans/deposit-a-plan/): the user guide
- [Deposit plugin guide](https://opencdmp.github.io/developers/plugins/deposit/): building your own deposit service

## Contributing

Report bugs and suggest changes in [GitHub Issues](https://github.com/OpenCDMP/repository-deposit-fedora/issues). See [Contributing](https://opencdmp.github.io/developers/contributing/) for how to propose a change.

## License

repository-deposit-fedora is licensed under the [European Union Public Licence (EUPL) v1.2](LICENSE).

## Contact

Questions: email opencdmp at cite.gr.
