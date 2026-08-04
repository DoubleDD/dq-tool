package com.example.dq.controller;

import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.DataSourceRequest;
import com.example.dq.model.TestConnectionRequest;
import com.example.dq.service.DataSourceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    private final DataSourceService service;

    public DataSourceController(DataSourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<DataSourceConfig> list() {
        return service.list();
    }

    @PostMapping
    public Map<String, Long> create(@Valid @RequestBody DataSourceRequest req) {
        return Map.of("id", service.create(req));
    }

    @PutMapping("/{id}")
    public void update(@PathVariable long id, @Valid @RequestBody DataSourceRequest req) {
        service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PostMapping("/test")
    public Map<String, Object> test(@Valid @RequestBody TestConnectionRequest req) {
        try {
            service.testConnection(req.jdbcUrl(), req.username(), req.password());
            return Map.of("success", true);
        } catch (SQLException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }
}
