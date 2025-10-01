package org.opencdmp.deposit.fedorarepository.service.fedora;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.cite.tools.logging.LoggerService;
import gr.cite.tools.logging.MapLogEntry;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.opencdmp.commonmodels.models.FileEnvelopeModel;
import org.opencdmp.commonmodels.models.plan.PlanModel;
import org.opencdmp.commonmodels.models.plugin.PluginUserFieldModel;
import org.opencdmp.deposit.fedorarepository.model.builder.FedoraBuilder;
import org.opencdmp.deposit.fedorarepository.service.storage.FileStorageService;
import org.opencdmp.depositbase.repository.DepositConfiguration;
import org.opencdmp.depositbase.repository.PlanDepositModel;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final FedoraServiceProperties fedoraServiceProperties;
    private final FedoraBuilder fedoraBuilder;
    private final FileStorageService storageService;
    private final ResourceLoader resourceLoader;

    private byte[] logo;
    private String username;
    private String password;

    @Autowired
    public FedoraDepositServiceImpl(FedoraServiceProperties fedoraServiceProperties, FedoraBuilder fedoraBuilder, FileStorageService storageService, ResourceLoader resourceLoader){
        this.fedoraServiceProperties = fedoraServiceProperties;
        this.fedoraBuilder = fedoraBuilder;
	    this.storageService = storageService;
        this.resourceLoader = resourceLoader;
        this.logo = null;
    }

    @Override
    public String deposit(PlanDepositModel planDepositModel) throws Exception {

        DepositConfiguration depositConfiguration = this.getConfiguration();

        if(depositConfiguration != null && planDepositModel != null && planDepositModel.getPlanModel() != null) {

            if (planDepositModel.getAuthInfo() != null) {
                if (planDepositModel.getAuthInfo().getAuthFields() != null && !planDepositModel.getAuthInfo().getAuthFields().isEmpty() && depositConfiguration.getUserConfigurationFields() != null) {
                    PluginUserFieldModel usernameFieldMode = planDepositModel.getAuthInfo().getAuthFields().stream().filter(x -> x.getCode().equals(CONFIGURATION_FIELD_USERNAME)).findFirst().orElse(null);
                    PluginUserFieldModel passwordFieldModel = planDepositModel.getAuthInfo().getAuthFields().stream().filter(x -> x.getCode().equals(CONFIGURATION_FIELD_PASSWORD)).findFirst().orElse(null);
                    if (usernameFieldMode != null && usernameFieldMode.getTextValue() != null && !usernameFieldMode.getTextValue().isBlank()
                        && passwordFieldModel != null && passwordFieldModel.getTextValue() != null && !passwordFieldModel.getTextValue().isBlank()) {
                        this.username = usernameFieldMode.getTextValue();
                        this.password = passwordFieldModel.getTextValue();
                    }
                }
            }

            if ((this.username == null || this.username.isBlank()) && (this.password == null || this.password.isBlank())) {
                this.username = this.fedoraServiceProperties.getUsername();
                this.password = this.fedoraServiceProperties.getPassword();
            }

            String baseUrl = depositConfiguration.getRepositoryUrl();

            WebClient client = this.getWebClient();

            DepositConfiguration config = this.fedoraServiceProperties.getDepositConfiguration();
            if (config == null) return null;

            String previousDOI = planDepositModel.getPlanModel().getPreviousDOI();

            try {

                if (previousDOI == null) {
                    return deposit(baseUrl, client, planDepositModel.getPlanModel());
                } else {
                    return depositNewVersion(baseUrl, client, planDepositModel.getPlanModel());
                }

            } catch (HttpClientErrorException | HttpServerErrorException ex) {
                logger.error(ex.getMessage(), ex);
                Map<String, String> parsedException = objectMapper.readValue(ex.getResponseBodyAsString(), Map.class);
                throw new IOException(parsedException.get("message"), ex);
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

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(this.username, this.password);
        return headers;
    }

    private String depositNewVersion(String baseUrl, WebClient client, PlanModel planModel){
        String resourceUri = baseUrl + planModel.getPreviousDOI();

        logger.debug(new MapLogEntry("Deposit New Version")
                .And("url", resourceUri + "/fcr:versions")
                .And("plan", planModel));

        String response = client.post().uri(resourceUri + "/fcr:versions").headers(httpHeaders -> {
                    httpHeaders.putAll(this.createHeaders());
                }).contentType(MediaType.valueOf("text/turtle"))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        client.put()
                .uri(resourceUri)
                .headers(httpHeaders -> httpHeaders.putAll(this.createHeaders()))
                .contentType(MediaType.valueOf("text/turtle"))
                .bodyValue(this.fedoraBuilder.build(planModel))
                .retrieve()
                .toBodilessEntity()
                .block();

        this.deleteFiles(resourceUri);

        this.uploadFiles(planModel, resourceUri);

        return planModel.getPreviousDOI();
    }

    private void deleteFiles(String resourceUri) {
        WebClient client = this.getWebClient();

        String turtle = client.get()
                .uri(resourceUri)
                .headers(httpHeaders -> httpHeaders.putAll(this.createHeaders()))
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
                        .headers(httpHeaders -> httpHeaders.putAll(this.createHeaders()))
                        .retrieve()
                        .toBodilessEntity()
                        .block();
            } catch (Exception e) {
                logger.error("Failed to delete child at {}", childUri, e);
            }
        }
    }


    private String deposit(String baseUrl, WebClient client, PlanModel planModel){
        String response;
        logger.debug(new MapLogEntry("Deposit")
                .And("url", baseUrl)
                .And("plan", planModel));

        response = client.post().uri(baseUrl).headers(httpHeaders -> {
            httpHeaders.putAll(this.createHeaders());
        }).contentType(MediaType.valueOf("text/turtle"))
        .bodyValue(this.fedoraBuilder.build(planModel))
        .retrieve()
        .bodyToMono(String.class)
        .block();

        if (response == null) return null;

        this.uploadFiles(planModel, response);

        return this.getIdFromHandle(response);
    }

    private void uploadFiles(PlanModel planModel, String url){
        if (planModel.getPdfFile() != null) this.uploadFile(planModel.getPdfFile(), url , "application/pdf", planModel.getVersion());
        if (planModel.getRdaJsonFile() != null) this.uploadFile(planModel.getRdaJsonFile(), url, "application/json", planModel.getVersion());
        if (planModel.getSupportingFilesZip() != null) this.uploadFile(planModel.getSupportingFilesZip(), url, "application/zip", planModel.getVersion());
    }

    private void uploadFile(FileEnvelopeModel fileEnvelopeModel, String baseUrl, String  contentType, int version) {

        byte[] content = null;
        if (this.getConfiguration().isUseSharedStorage() && fileEnvelopeModel.getFileRef() != null && !fileEnvelopeModel.getFileRef().isBlank()) {
            content = this.storageService.readFile(fileEnvelopeModel.getFileRef());
        }
        if (content == null || content.length == 0){
            content = fileEnvelopeModel.getFile();
        }

        String url = baseUrl + "/" + cleanFileName(fileEnvelopeModel.getFilename(), version);

        this.getWebClient().put().uri(url).headers(httpHeaders -> {
                    httpHeaders.putAll(this.createHeaders());
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
    
    @Override
    public String authenticate(String code){
        return null;
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
                return response.mutate().build().bodyToMono(String.class)
                    .flatMap(body -> {
                        logger.error(new MapLogEntry("Response").And("method", response.request().getMethod().toString()).And("url", response.request().getURI()).And("status", response.statusCode().toString()).And("body", body));
                        return Mono.just(response);
                    });
            }
            return Mono.just(response);
            
        });
    }
}
