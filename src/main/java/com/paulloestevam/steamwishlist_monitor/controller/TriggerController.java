package com.paulloestevam.steamwishlist_monitor.controller;

import com.paulloestevam.steamwishlist_monitor.service.SteamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api")
@Slf4j
public class TriggerController {

    private final SteamService steamService;

    public TriggerController(SteamService steamService) {
        this.steamService = steamService;
    }

    /**
     * Endpoint para disparar manualmente a busca de ofertas e envio de e-mail.
     * Útil para testes sem esperar o cron.
     *
     * Uso: GET http://localhost:8080/api/trigger
     *      (ou no Raspberry: GET http://<ip-do-raspberry>:8080/api/trigger)
     */
    @GetMapping("/trigger")
    public ResponseEntity<String> triggerNow() {
        String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        log.info("Trigger manual iniciado em {}", startTime);

        try {
            steamService.fetchDeals();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            String err = "❌ Erro ao executar trigger: " + e.getMessage();
            log.error(err, e);
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /**
     * Health check simples para verificar se a aplicação está no ar.
     *
     * Uso: GET http://localhost:8080/api/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        return ResponseEntity.ok("🟢 Steam Wishlist Monitor online — " + time);
    }
}
