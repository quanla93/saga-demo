package com.quanla.sagademo.order.api;

import com.quanla.sagademo.order.dlt.DltMessageRecord;
import com.quanla.sagademo.order.dlt.DltMessageRepository;
import com.quanla.sagademo.order.dlt.DltMessageStatus;
import com.quanla.sagademo.order.dlt.DltReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/dlt")
@RequiredArgsConstructor
public class DltAdminController {

    private final DltMessageRepository dltMessageRepository;
    private final DltReplayService dltReplayService;

    @GetMapping
    public List<DltMessageRecord> list(@RequestParam(required = false) DltMessageStatus status) {
        if (status == null) {
            return dltMessageRepository.findAll();
        }
        return dltMessageRepository.findTop100ByStatusOrderByFirstSeenAtDesc(status);
    }

    @GetMapping("/{id}")
    public DltMessageRecord get(@PathVariable UUID id) {
        return dltMessageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DLT message not found: " + id));
    }

    @PostMapping("/{id}/replay")
    public DltMessageRecord replay(@PathVariable UUID id, @RequestBody(required = false) DltActionRequest request) {
        DltActionRequest action = request == null ? new DltActionRequest(null, null) : request;
        return dltReplayService.replay(id, action.operatorOrDefault(), action.reason());
    }

    @PostMapping("/{id}/quarantine")
    public DltMessageRecord quarantine(@PathVariable UUID id, @RequestBody(required = false) DltActionRequest request) {
        DltActionRequest action = request == null ? new DltActionRequest(null, null) : request;
        return dltReplayService.quarantine(id, action.operatorOrDefault(), action.reason());
    }

    public record DltActionRequest(String operator, String reason) {
        String operatorOrDefault() {
            return operator == null || operator.isBlank() ? "local-operator" : operator;
        }
    }
}
