package com.maniram.textto3d;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class GenerationController {

    private final ShapEClient shapE;
    /** Jobs in memory: taskId -> job state (and the finished .glb bytes). */
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    /** Generation takes 20-60s, so it runs in the background (Java 21 virtual threads). */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GenerationController(ShapEClient shapE) {
        this.shapE = shapE;
    }

    public record GenerateRequest(@NotBlank @Size(max = 500) String prompt) {}

    static final class Job {
        volatile String status = "running"; // running | success | failed
        volatile String error;
        volatile byte[] glb;
    }

    @PostMapping("/generate")
    public Map<String, String> generate(@Valid @RequestBody GenerateRequest req) {
        if (jobs.size() > 50) jobs.values().removeIf(j -> !"running".equals(j.status)); // bound memory

        String taskId = UUID.randomUUID().toString();
        Job job = new Job();
        jobs.put(taskId, job);

        executor.submit(() -> {
            try {
                String url = shapE.generate(req.prompt().trim());
                job.glb = shapE.download(url); // download now: the file link on the Space is temporary
                job.status = "success";
            } catch (Exception e) {
                job.error = e.getMessage();
                job.status = "failed";
            }
        });
        return Map.of("taskId", taskId);
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String taskId) {
        Job job = jobs.get(taskId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown task"));
        }
        Map<String, Object> body = new HashMap<>(); // HashMap because error may be null
        body.put("status", job.status);
        body.put("ready", "success".equals(job.status));
        body.put("failed", "failed".equals(job.status));
        body.put("error", job.error);
        return ResponseEntity.ok(body);
    }

    /** Serves the .glb for the viewer; ?download=true makes the browser save it as a file. */
    @GetMapping("/model/{taskId}")
    public ResponseEntity<?> model(@PathVariable String taskId,
                                   @RequestParam(defaultValue = "false") boolean download) {
        Job job = jobs.get(taskId);
        if (job == null || job.glb == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Model is not ready yet"));
        }
        String disposition = (download ? "attachment" : "inline") + "; filename=\"model-" + taskId + ".glb\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "model/gltf-binary")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(job.glb);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> badRequest() {
        return ResponseEntity.badRequest().body(Map.of("error", "Prompt must be 1-500 characters"));
    }
}
