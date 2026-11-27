package com.kc1vmz.netcentral.controller;

import com.kc1vmz.netcentral.accessor.StripLibraryAccessor;
import com.kc1vmz.netcentral.accessor.StripLibraryAccessor.Input;
import com.kc1vmz.netcentral.accessor.StripLibraryAccessor.Library;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/strip-library")
public class StripLibraryController {
    private final StripLibraryAccessor library;
    public StripLibraryController(StripLibraryAccessor library) { this.library = library; }
    @GetMapping public Library list() { return library.list(); }
    @PostMapping public Library create(@RequestBody Input input) { return library.save(null, input); }
    @PutMapping("/{id}") public Library update(@PathVariable String id, @RequestBody Input input) { return library.save(id, input); }
    @DeleteMapping("/{id}") public Library delete(@PathVariable String id, @RequestParam long version) { return library.delete(id, version); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> invalid(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String,String>> forbidden(AccessDeniedException error) {
        return ResponseEntity.status(403).body(Map.of("message", error.getMessage()));
    }
}

