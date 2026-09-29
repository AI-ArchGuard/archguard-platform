package io.github.aiarchguard.platform.agentdocument.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agentdocument.DocumentFragmentView;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.agentdocument.DocumentPage;
import io.github.aiarchguard.platform.agentdocument.DocumentTooLargeException;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionPage;
import io.github.aiarchguard.platform.agentdocument.InvalidDocumentException;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/documents")
final class DocumentController {
    private final DocumentOperations documents;
    private final ObjectMapper mapper;

    DocumentController(DocumentOperations documents, ObjectMapper mapper) {
        this.documents = documents;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentVersionView> upload(@PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("documentKey") String documentKey,
            @RequestParam("file") MultipartFile file) {
        if (file.getSize() > 262144) throw new DocumentTooLargeException();
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException exception) { throw new InvalidDocumentException("Document could not be read"); }
        var uploaded = documents.upload(projectId, documentKey, idempotencyKey, file.getContentType(),
            file.getOriginalFilename(), bytes);
        URI location = URI.create("/api/v1/projects/" + projectId + "/documents/"
            + uploaded.version().documentId() + "/versions/" + uploaded.version().id());
        return ResponseEntity.status(uploaded.replay() ? 200 : 201).location(location).body(uploaded.version());
    }

    @GetMapping
    DocumentPage list(@PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return documents.list(projectId, page, size);
    }

    @GetMapping("/{documentId}/versions")
    DocumentVersionPage listVersions(@PathVariable UUID projectId, @PathVariable UUID documentId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return documents.listVersions(projectId, documentId, page, size);
    }

    @GetMapping("/{documentId}/versions/{versionId}")
    DocumentVersionView get(@PathVariable UUID projectId, @PathVariable UUID documentId,
            @PathVariable UUID versionId) {
        return documents.getVersion(projectId, documentId, versionId);
    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    SearchResponse search(@PathVariable UUID projectId, @RequestBody String json) {
        SearchRequest request;
        try { request = mapper.readValue(json, SearchRequest.class); }
        catch (JsonProcessingException exception) { throw new InvalidDocumentException("Search request is invalid"); }
        return new SearchResponse(documents.search(projectId, request.versionIds(), request.query()));
    }

    private record SearchRequest(List<UUID> versionIds, String query) {}
    private record SearchResponse(List<DocumentFragmentView> items) {}
}
