package com.example.dq.controller;

import com.example.dq.model.TableStat;
import com.example.dq.service.MetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;

@RestController
@RequestMapping("/api/datasources/{dsId}")
public class MetadataController {

    private final MetadataService service;

    public MetadataController(MetadataService service) {
        this.service = service;
    }

    @GetMapping("/databases")
    public List<String> listDatabases(@PathVariable long dsId) throws SQLException {
        return service.listDatabases(dsId);
    }

    @GetMapping("/schemas")
    public List<String> listSchemas(@PathVariable long dsId,
                                    @RequestParam(required = false) String db) throws SQLException {
        return service.listSchemas(dsId, db);
    }

    @GetMapping("/schemas/{schema}/tables")
    public List<TableStat> listTables(@PathVariable long dsId, @PathVariable String schema,
                                      @RequestParam(required = false) String db) throws SQLException {
        return service.listTables(dsId, db, schema);
    }
}
