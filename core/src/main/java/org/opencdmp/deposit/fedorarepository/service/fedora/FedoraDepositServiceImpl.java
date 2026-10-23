package org.opencdmp.deposit.fedorarepository.service.fedora;

import org.springframework.web.reactive.function.client.WebClientResponseException;
import gr.cite.tools.exception.MyValidationException;
import gr.cite.tools.logging.LoggerService;
import gr.cite.tools.logging.MapLogEntry;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import gr.cite.tools.exception.MyApplicationException;
import org.opencdmp.commonmodels.models.FileEnvelopeModel;
import org.opencdmp.commonmodels.models.deposit.DepositPreviewModel;
import org.opencdmp.commonmodels.models.plan.PlanModel;
import org.opencdmp.commonmodels.models.plugin.PluginUserFieldModel;
import org.opencdmp.deposit.fedorarepository.configuration.semantics.FedoraSemanticsManifest;
import org.opencdmp.deposit.fedorarepository.configuration.semantics.SemanticsProperties;
import org.opencdmp.deposit.fedorarepository.model.builder.FedoraBuilder;
import org.opencdmp.deposit.fedorarepository.model.builder.FedoraPreviewMapper;
import org.opencdmp.deposit.fedorarepository.service.storage.FileStorageService;
import org.opencdmp.depositbase.repository.DepositAccount;
import org.opencdmp.depositbase.repository.DepositConfiguration;
import org.opencdmp.depositbase.repository.DepositPluginSettings;
import org.opencdmp.depositbase.repository.DepositPreviewMergeHelper;
import org.opencdmp.depositbase.repository.PlanDepositModel;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.*;
import java.util.*;


import reactor.netty.http.client.HttpClient;

@Component
public class FedoraDepositServiceImpl implements FedoraDepositService {
    private static final LoggerService logger = new LoggerService(LoggerFactory.getLogger(FedoraDepositServiceImpl.class));

    private static final String CONFIGURATION_FIELD_USERNAME = "fedora-username";
    private static final String CONFIGURATION_FIELD_PASSWORD = "fedora-password";
    private static final String SETTING_ORGANISATION_USERNAME = "organisation-username";
    private static final String SETTING_ORGANISATION_PASSWORD = "organisation-password";
    private static final String SETTING_CONTAINER = "container";


    private final FedoraServiceProperties fedoraServiceProperties;
    private final FedoraBuilder fedoraBuilder;
    private final FileStorageService storageService;
    private final ResourceLoader resourceLoader;
    private final FedoraPreviewMapper fedoraPreviewMapper;

    private byte[] logo;

    @Autowired
    public FedoraDepositServiceImpl(FedoraServiceProperties fedoraServiceProperties, FedoraBuilder fedoraBuilder, FileStorageService storageService, ResourceLoader resourceLoader, FedoraPreviewMapper fedoraPreviewMapper, SemanticsProperties semanticsProperties){
        this.fedoraServiceProperties = fedoraServiceProperties;
        this.fedoraBuilder = fedoraBuilder;
	    this.storageService = storageService;
        this.resourceLoader = resourceLoader;
        this.fedoraPreviewMapper = fedoraPreviewMapper;
        this.fedoraServiceProperties.getDepositConfiguration().setSemantics(FedoraSemanticsManifest.build(semanticsProperties));
        this.logo = null;
    }

    @Override
    public String deposit(PlanDepositModel planDepositModel) {

        DepositConfiguration depositConfiguration = this.getConfiguration();

        if(depositConfiguration != null && planDepositModel != null && planDepositModel.getPlanModel() != null) {

            DepositPluginSettings settings = DepositPluginSettings.of(planDepositModel.getPlanModel(), depositConfiguration);
            Credentials credentials = this.credentials(planDepositModel, depositConfiguration, settings);

            HttpHeaders auth = this.createHeaders(credentials.username(), credentials.password());

            String baseUrl = depositConfiguration.getRepositoryUrl();

            WebClient client = this.getWebClient();

            DepositConfiguration config = this.fedoraServiceProperties.getDepositConfiguration();
            if (config == null) return null;

            String previousDOI = planDepositModel.getPlanModel().getPreviousDOI();

            try {

                if (previousDOI == null) {
                    return deposit(baseUrl, client, planDepositModel, settings, auth);
                } else {
                    return depositNewVersion(baseUrl, client, planDepositModel, settings, auth);
                }

            } catch (WebClientResponseException ex) {
                throw this.toException(ex);
            }

        }

        return null;

    }

    private String getIdFromHandle(String url){

        String[] parts = url.split("/");

        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty()) {
                return parts[i];
            }
        }

        throw new IllegalArgumentException("No identifier found in URL");
    }

    private HttpHeaders createHeaders(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, password);
        return headers;
    }

    /** The container new records are created in, relative to the repository url and without surrounding slashes; empty for the root. */
    private String container(DepositPluginSettings settings) {
        String container = settings.text(SETTING_CONTAINER, this.fedoraServiceProperties.getCollection());
        if (container == null) return "";
        container = container.trim();
        while (container.startsWith("/")) container = container.substring(1);
        while (container.endsWith("/")) container = container.substring(0, container.length() - 1);
        return container;
    }

    private String depositNewVersion(String baseUrl, WebClient client, PlanDepositModel planDepositModel, DepositPluginSettings settings, HttpHeaders auth){
        PlanModel planModel = planDepositModel.getPlanModel();
        String resourceUri = baseUrl + planModel.getPreviousDOI();

        logger.debug(new MapLogEntry("Deposit New Version")
                .And("url", resourceUri + "/fcr:versions")
                .And("plan", planModel));

        String response = client.post().uri(resourceUri + "/fcr:versions").headers(httpHeaders -> {
                    httpHeaders.putAll(auth);
                }).contentType(MediaType.valueOf("text/turtle"))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        Model body = this.fedoraBuilder.build(planModel);
        this.fedoraPreviewMapper.applyOverrides(body, planDepositModel.getEditedFields(), planDepositModel.getEditedGroups(), settings);

        client.put()
                .uri(resourceUri)
                .headers(httpHeaders -> httpHeaders.putAll(auth))
                .contentType(MediaType.valueOf("text/turtle"))
                .bodyValue(this.fedoraBuilder.serialize(body))
                .retrieve()
                .toBodilessEntity()
                .block();

        this.deleteFiles(resourceUri, auth);

        this.uploadFiles(planModel, resourceUri, auth);

        return planModel.getPreviousDOI();
    }

    private void deleteFiles(String resourceUri, HttpHeaders auth) {
        WebClient client = this.getWebClient();

        String turtle = client.get()
                .uri(resourceUri)
                .headers(httpHeaders -> httpHeaders.putAll(auth))
                .accept(MediaType.valueOf("text/turtle"))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (turtle == null || turtle.isBlank()) {
            logger.warn("No RDF content found at: {}", resourceUri);
        }

        Model model = ModelFactory.createDefaultModel();
        try (StringReader reader = new StringReader(turtle)) {
            model.read(reader, null, "TURTLE");
        }

        org.apache.jena.rdf.model.Resource resource = model.createResource(resourceUri);
        Property ldpContains = model.createProperty("http://www.w3.org/ns/ldp#contains");

        List<String> childUris = model.listObjectsOfProperty(resource, ldpContains)
                .mapWith(RDFNode::asResource)
                .mapWith(org.apache.jena.rdf.model.Resource::getURI)
                .toList();

        for (String childUri : childUris) {
            try {
                logger.debug("Deleting child: {}", childUri);
                client.delete()
                        .uri(childUri)
                        .headers(httpHeaders -> httpHeaders.putAll(auth))
                        .retrieve()
                        .toBodilessEntity()
                        .block();
            } catch (Exception e) {
                logger.error("Failed to delete child at {}", childUri, e);
            }
        }
    }


    private String deposit(String baseUrl, WebClient client, PlanDepositModel planDepositModel, DepositPluginSettings settings, HttpHeaders auth){
        String response;
        PlanModel planModel = planDepositModel.getPlanModel();
        String container = this.container(settings);
        String containerUrl = container.isEmpty() ? baseUrl : (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + container;
        logger.debug(new MapLogEntry("Deposit")
                .And("url", containerUrl)
                .And("plan", planModel));

        Model model = this.fedoraBuilder.build(planModel);
        this.fedoraPreviewMapper.applyOverrides(model, planDepositModel.getEditedFields(), planDepositModel.getEditedGroups(), settings);
        response = client.post().uri(containerUrl).headers(httpHeaders -> {
            httpHeaders.putAll(auth);
        }).contentType(MediaType.valueOf("text/turtle"))
        .bodyValue(this.fedoraBuilder.serialize(model))
        .retrieve()
        .onStatus(status -> !container.isEmpty() && status.value() == HttpStatus.NOT_FOUND.value(),
                x -> Mono.error(new MyValidationException("The container '" + container + "' does not exist in the repository. Create it there or change the container setting.")))
        .bodyToMono(String.class)
        .block();

        if (response == null) return null;

        try {
            this.uploadFiles(planModel, response, auth);
        } catch (RuntimeException ex) {
            this.deleteRecord(response, auth);
            throw ex;
        }

        // the identifier is the record's path relative to the repository url, which is where new versions look for it
        String id = this.getIdFromHandle(response);
        return container.isEmpty() ? id : container + "/" + id;
    }

    /** Deletes the record and its tombstone, so nothing is left behind in the repository. */
    private void deleteRecord(String recordUrl, HttpHeaders auth) {
        WebClient client = this.getWebClient();
        try {
            client.delete().uri(recordUrl).headers(httpHeaders -> httpHeaders.putAll(auth)).retrieve().toBodilessEntity().block();
            client.delete().uri(recordUrl + "/fcr:tombstone").headers(httpHeaders -> httpHeaders.putAll(auth)).retrieve().toBodilessEntity().block();
        } catch (Exception e) {
            logger.warn(new MapLogEntry("Could not remove the record").And("url", recordUrl).And("error", e.getMessage()));
        }
    }

    /** What the repository answered, as the user should see it; its errors are plain text, its authentication errors a server page. */
    private RuntimeException toException(WebClientResponseException ex) {
        logger.error(ex.getMessage(), ex);
        int status = ex.getStatusCode().value();
        if (status == HttpStatus.UNAUTHORIZED.value()) return new MyValidationException("The repository did not accept the account's username and password.");
        if (status == HttpStatus.FORBIDDEN.value()) return new MyValidationException("The account is not allowed to add records to the repository.");
        String body = ex.getResponseBodyAsString();
        boolean text = ex.getHeaders().getContentType() == null || !MediaType.TEXT_HTML.isCompatibleWith(ex.getHeaders().getContentType());
        String message = text && body != null && !body.isBlank() ? body.trim() : ex.getStatusText();
        if (message.startsWith("Error: ")) message = message.substring("Error: ".length());
        if (ex.getStatusCode().is4xxClientError()) return new MyValidationException("The repository refused the deposit: " + message);
        return new MyApplicationException("The repository could not take the deposit: " + message);
    }

    private void uploadFiles(PlanModel planModel, String url, HttpHeaders auth){
        if (planModel.getPdfFile() != null) this.uploadFile(planModel.getPdfFile(), url , "application/pdf", planModel.getVersion(), auth);
        if (planModel.getRdaJsonFile() != null) this.uploadFile(planModel.getRdaJsonFile(), url, "application/json", planModel.getVersion(), auth);
        if (planModel.getSupportingFilesZip() != null) this.uploadFile(planModel.getSupportingFilesZip(), url, "application/zip", planModel.getVersion(), auth);
    }

    private void uploadFile(FileEnvelopeModel fileEnvelopeModel, String baseUrl, String  contentType, int version, HttpHeaders auth) {

        byte[] content = null;
        if (this.getConfiguration().isUseSharedStorage() && fileEnvelopeModel.getFileRef() != null && !fileEnvelopeModel.getFileRef().isBlank()) {
            content = this.storageService.readFile(fileEnvelopeModel.getFileRef());
        }
        if (content == null || content.length == 0){
            content = fileEnvelopeModel.getFile();
        }

        String url = baseUrl + "/" + cleanFileName(fileEnvelopeModel.getFilename(), version);

        this.getWebClient().put().uri(url).headers(httpHeaders -> {
                    httpHeaders.putAll(auth);
                })
                .contentType(MediaType.valueOf(contentType))
                .body(BodyInserters
                        .fromResource(new ByteArrayResource(content)))
                .retrieve().toEntity(String.class).block();
    }

    private static String cleanFileName(String name, int version){
        if (name == null || name.isEmpty()) return null;

        int extensionIndex = name.lastIndexOf('.');
        String extension = "";
        String namePart = "";
        if (extensionIndex > 0) {
            extension = name.substring(extensionIndex + 1);
            namePart = name.substring(0, extensionIndex);
        }

        if (!namePart.isEmpty()) namePart = namePart.replaceAll("[^a-zA-Z0-9_+ ]", "").replace(" ", "_").replace(",", "_");

        return namePart + "_V" + version + "." + extension;
    }

    @Override
    public DepositConfiguration getConfiguration() {
        return this.fedoraServiceProperties.getDepositConfiguration();
    }

    private record Credentials(String username, String password) {}

    private Credentials credentials(PlanDepositModel planDepositModel, DepositConfiguration depositConfiguration, DepositPluginSettings settings) {
        String username = null;
        String password = null;

        if (planDepositModel.getAuthInfo() != null) {
            if (planDepositModel.getAuthInfo().getAuthFields() != null && !planDepositModel.getAuthInfo().getAuthFields().isEmpty() && depositConfiguration.getUserConfigurationFields() != null) {
                PluginUserFieldModel usernameFieldMode = planDepositModel.getAuthInfo().getAuthFields().stream().filter(x -> x.getCode().equals(CONFIGURATION_FIELD_USERNAME)).findFirst().orElse(null);
                PluginUserFieldModel passwordFieldModel = planDepositModel.getAuthInfo().getAuthFields().stream().filter(x -> x.getCode().equals(CONFIGURATION_FIELD_PASSWORD)).findFirst().orElse(null);
                if (usernameFieldMode != null && usernameFieldMode.getTextValue() != null && !usernameFieldMode.getTextValue().isBlank()
                    && passwordFieldModel != null && passwordFieldModel.getTextValue() != null && !passwordFieldModel.getTextValue().isBlank()) {
                    username = usernameFieldMode.getTextValue();
                    password = passwordFieldModel.getTextValue();
                }
            }
        }

        if ((username == null || username.isBlank()) && (password == null || password.isBlank())) {
            username = settings.text(SETTING_ORGANISATION_USERNAME);
            password = settings.text(SETTING_ORGANISATION_PASSWORD);
        }

        if ((username == null || username.isBlank()) && (password == null || password.isBlank())) {
            username = this.fedoraServiceProperties.getUsername();
            password = this.fedoraServiceProperties.getPassword();
        }
        return new Credentials(username, password);
    }

    @Override
    public DepositAccount account(PlanDepositModel planDepositModel) {
        DepositConfiguration depositConfiguration = this.getConfiguration();
        if (depositConfiguration == null || planDepositModel == null || planDepositModel.getPlanModel() == null) return null;

        try {
            // Fedora has no endpoint that describes an account, so the account is the username the deposit signed in with; the password is never returned
            String username = this.credentials(planDepositModel, depositConfiguration, DepositPluginSettings.of(planDepositModel.getPlanModel(), depositConfiguration)).username();
            if (username == null || username.isBlank()) return null;

            DepositAccount account = new DepositAccount();
            account.setId(username);
            account.setName(username);
            return account;
        } catch (Exception ex) {
            logger.error("could not tell the Fedora account of the deposit", ex);
            return null;
        }
    }

    @Override
    public String authenticate(String code){
        return null;
    }

    @Override
    public DepositPreviewModel preview(PlanDepositModel planDepositModel) {
        if (planDepositModel == null || planDepositModel.getPlanModel() == null) return null;
        Model dataset = this.fedoraBuilder.build(planDepositModel.getPlanModel());
        List<String> uploadFileNames = new ArrayList<>();
        PlanModel planModel = planDepositModel.getPlanModel();
        if (planModel.getPdfFile() != null && planModel.getPdfFile().getFilename() != null) uploadFileNames.add(planModel.getPdfFile().getFilename());
        if (planModel.getRdaJsonFile() != null && planModel.getRdaJsonFile().getFilename() != null) uploadFileNames.add(planModel.getRdaJsonFile().getFilename());
        if (planModel.getSupportingFilesZip() != null && planModel.getSupportingFilesZip().getFilename() != null) uploadFileNames.add(planModel.getSupportingFilesZip().getFilename());
        return this.fedoraPreviewMapper.toPreview(dataset, uploadFileNames, DepositPluginSettings.of(planModel, this.getConfiguration()));
    }

    @Override
    public String getLogo() {
        DepositConfiguration fedoraConfig = this.fedoraServiceProperties.getDepositConfiguration();
        if(fedoraConfig != null && fedoraConfig.isHasLogo() && this.fedoraServiceProperties.getLogo() != null && !this.fedoraServiceProperties.getLogo().isBlank()) {
            if (this.logo == null) {
                try {
                    Resource resource = resourceLoader.getResource(this.fedoraServiceProperties.getLogo());
                    if(!resource.isReadable()) return null;
                    try(InputStream inputStream = resource.getInputStream()) {
                        this.logo = inputStream.readAllBytes();
                    }
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }
            return (this.logo != null && this.logo.length != 0) ? Base64.getEncoder().encodeToString(this.logo) : null;
        }
        return null;
    }

    private WebClient getWebClient() {
        HttpClient httpClient = HttpClient.create()
                .followRedirect(true);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filters(exchangeFilterFunctions -> {
                    exchangeFilterFunctions.add(logRequest());
                    exchangeFilterFunctions.add(logResponse());
                })
                .codecs(codecs -> codecs
                        .defaultCodecs()
                        .maxInMemorySize(this.fedoraServiceProperties.getMaxInMemorySizeInBytes())
                )
                .build();
    }

    private static ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            logger.debug(new MapLogEntry("Request").And("method", clientRequest.method().toString()).And("url", clientRequest.url().toString()));
            return Mono.just(clientRequest);
        });
    }

    private static ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (response.statusCode().isError()) {
                // reading the body consumes it, so the response passed on gets it back for the caller to read the repository's message
                return response.bodyToMono(String.class).defaultIfEmpty("")
                    .map(body -> {
                        logger.error(new MapLogEntry("Response").And("method", response.request().getMethod().toString()).And("url", response.request().getURI()).And("status", response.statusCode().toString()).And("body", body));
                        return response.mutate().body(body).build();
                    });
            }
            return Mono.just(response);
            
        });
    }
}
