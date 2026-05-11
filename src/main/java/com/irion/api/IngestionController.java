package com.irion.api;

import com.irion.domain.IngestionResult;
import com.irion.ingestion.DataIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * REST endpoints for data ingestion: upload CSV/JSON files into the Irion data platform.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ingestion")
@CrossOrigin(origins = "*")
@Tag(name = "Ingestion", description = "Upload and ingest CSV or JSON data files")
public class IngestionController {

    private final DataIngestionService dataIngestionService;

    public IngestionController(DataIngestionService dataIngestionService) {
        this.dataIngestionService = dataIngestionService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload and ingest a CSV or JSON data file",
               description = "Accepts a multipart file upload, saves it to a temporary location, "
                           + "and runs the full ingestion pipeline: schema detection, Parquet conversion, "
                           + "and DuckDB view mounting. The temporary file is cleaned up afterwards.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Ingestion result with row count and status",
                     content = @Content(schema = @Schema(implementation = IngestionResult.class))),
        @ApiResponse(responseCode = "500", description = "Ingestion failed")
    })
    public ResponseEntity<IngestionResult> upload(
            @Parameter(description = "CSV or JSON file to ingest", required = true)
            @RequestParam("file") MultipartFile file) {
        log.info("POST /upload file={} size={}", file.getOriginalFilename(), file.getSize());

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("irion-ingest-", "-" + file.getOriginalFilename());
            file.transferTo(tempFile.toFile());
            log.debug("Saved upload to temp file: {}", tempFile);

            IngestionResult result = dataIngestionService.ingest(tempFile);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            IngestionResult failed = IngestionResult.builder()
                    .fileName(file.getOriginalFilename())
                    .rowsIngested(0)
                    .durationMs(0)
                    .status("FAILED")
                    .errorMessage("Upload processing error: " + e.getMessage())
                    .build();
            return ResponseEntity.status(500).body(failed);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.warn("Failed to delete temp file {}: {}", tempFile, e.getMessage());
                }
            }
        }
    }
}
