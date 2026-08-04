package com.paulloestevam.steamwishlist_monitor.service;

import com.paulloestevam.steamwishlist_monitor.config.SteamWishlistConfig;
import com.paulloestevam.steamwishlist_monitor.model.Game;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@Service
@Slf4j
public class SteamService {

    private final JavaMailSender mailSender;
    private final SteamWishlistConfig config;
    private static final String LOG_DIR = "logs";

    @Value("${webdriver.chrome.driver-path:}")
    private String chromeDriverPath;

    @Value("${email.sender}")
    private String emailSender;

    @Value("${minDiscountPercentage:70}")
    private Integer minDiscountPercentage;

    public SteamService(JavaMailSender mailSender, SteamWishlistConfig config) {
        this.mailSender = mailSender;
        this.config = config;
    }

    public void fetchDeals() {
        if (chromeDriverPath != null && !chromeDriverPath.isBlank()) {
            System.setProperty("webdriver.chrome.driver", chromeDriverPath);
        }

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless", "--no-sandbox", "--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--lang=pt-BR");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-web-resources");
        options.addArguments("disable-plugins");
        options.addArguments("disable-images");
        options.addArguments("--disable-gpu");
        
        // Headers mais realistas
        options.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");
        
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("intl.accept_languages", "pt-BR,pt;q=0.9,en;q=0.8");
        prefs.put("profile.default_content_settings.popups", 0);
        options.setExperimentalOption("prefs", prefs);

        WebDriver driver = null;

        try {
            driver = new ChromeDriver(options);
            // Tenta aplicar headers HTTP adicionais via CDP para reduzir detecção e bloqueio
            try {
                Map<String, Object> enableParams = new HashMap<>();
                ((ChromeDriver) driver).executeCdpCommand("Network.enable", enableParams);

                Map<String, String> extraHeaders = new HashMap<>();
                extraHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
                extraHeaders.put("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8");
                extraHeaders.put("Accept-Encoding", "gzip, deflate, br");
                extraHeaders.put("Referer", "https://store.steampowered.com/");
                extraHeaders.put("DNT", "1");
                extraHeaders.put("Upgrade-Insecure-Requests", "1");
                extraHeaders.put("Sec-Fetch-Dest", "document");
                extraHeaders.put("Sec-Fetch-Mode", "navigate");
                extraHeaders.put("Sec-Fetch-Site", "none");
                extraHeaders.put("Sec-Fetch-User", "?1");
                extraHeaders.put("sec-ch-ua", "\"Chromium\";v=\"121\", \"Not)A;Brand\";v=\"8\", \"Google Chrome\";v=\"121\"");
                extraHeaders.put("sec-ch-ua-mobile", "?0");
                extraHeaders.put("sec-ch-ua-platform", "\"Windows\"");

                Map<String, Object> setHeadersParams = new HashMap<>();
                setHeadersParams.put("headers", extraHeaders);
                ((ChromeDriver) driver).executeCdpCommand("Network.setExtraHTTPHeaders", setHeadersParams);
                log.info("Headers extras aplicados via CDP");
            } catch (Exception e) {
                // Se o CDP não estiver disponível, apenas registra e segue
                log.warn("Não foi possível aplicar headers via CDP: {}", e.getMessage());
            }
            // Usamos um Map para garantir que cada URL de jogo apareça apenas uma vez
            Map<String, Game> uniqueGames = new HashMap<>();
            boolean processedSuccessfully = false;

            String url = config.getUrl();
            log.info("Acessando wishlist: {}", url);
            try {
                fetchWishlistWithRetry(driver, url, uniqueGames);
                processedSuccessfully = true;
            } catch (Exception e) {
                log.error("❌ Erro ao processar URL {}: {}", url, e.getMessage());
            }

            List<Game> allGamesFound = new ArrayList<>(uniqueGames.values());

            if (!allGamesFound.isEmpty()) {
                allGamesFound.sort(Comparator.comparingInt(Game::getDiscountPercentage).reversed());
                log.info("Total de ofertas: {}", allGamesFound.size());

                String emailBody = generatePlainTextEmail(allGamesFound);
                String lastLogContent = getLastLogContent();
                saveLogFile(emailBody);

                if (!lastLogContent.trim().equals(emailBody.trim())) {
                    log.info(allGamesFound.size() + " novas ofertas detectadas! Enviando e-mail...");
                    sendEmail(emailSender, "Steam Wishlist - " + allGamesFound.size() + " Ofertas", emailBody);
                } else {
                    log.info("Nenhuma mudança nas ofertas em relação ao último log. E-mail não enviado.");
                }
            } else if (processedSuccessfully) {
                // Só mostra "nenhuma oferta" se o processamento foi bem-sucedido
                log.warn("Nenhuma oferta acima de {}% encontrada.", minDiscountPercentage);
            }

        } catch (Exception e) {
            log.error("Erro fatal:", e);
        } finally {
            if (driver != null) driver.quit();
        }
    }

    /**
     * Busca a wishlist com retry automático em caso de rate limiting
     */
    private void fetchWishlistWithRetry(WebDriver driver, String url, Map<String, Game> uniqueGames) throws InterruptedException {
        int maxRetries = 3;
        int attempt = 0;
        long baseWaitTime = 10000; // 10 segundos

        while (attempt < maxRetries) {
            try {
                driver.get(url);
                driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(15));

                performScroll(driver);

                String pageSource = driver.getPageSource();
                saveDebugHtml(pageSource);

                // Verifica se Steam bloqueou a requisição por rate limiting
                if (isRateLimitError(pageSource)) {
                    attempt++;
                    if (attempt < maxRetries) {
                        long waitTime = baseWaitTime * (long) Math.pow(2, attempt - 1);
                        log.warn("❌ Rate limit detectado! Tentativa {}/{}. Aguardando {} segundos...", 
                                attempt, maxRetries, waitTime / 1000);
                        Thread.sleep(waitTime);
                        continue;
                    } else {
                        throw new RuntimeException("Rate limit persistente após " + maxRetries + " tentativas");
                    }
                }

                // Sucesso - processa o HTML
                Document document = Jsoup.parse(pageSource);
                Elements priceElements = document.select("[aria-label*='de desconto']");

                for (Element priceEl : priceElements) {
                    processPriceElement(priceEl, url, uniqueGames);
                }
                
                log.info("✅ Wishlist processada com sucesso!");
                return;

            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Rate limit")) {
                    throw e;
                }
                attempt++;
                if (attempt < maxRetries) {
                    long waitTime = baseWaitTime * (long) Math.pow(2, attempt - 1);
                    log.warn("Erro ao buscar wishlist (tentativa {}/{}): {}. Aguardando {} segundos...", 
                            attempt, maxRetries, e.getMessage(), waitTime / 1000);
                    Thread.sleep(waitTime);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Verifica se a página contém erro de rate limiting do Steam
     */
    private boolean isRateLimitError(String pageSource) {
        return pageSource.contains("Você realizou solicitações demais recentemente") || 
               pageSource.contains("solicitações demais") ||
               pageSource.contains("HTTP 429") ||
               pageSource.contains("Too Many Requests");
    }

    private void processPriceElement(Element priceEl, String baseUrl, Map<String, Game> uniqueGames) {
        try {
            String label = priceEl.attr("aria-label");
            int discount = Integer.parseInt(label.split("%")[0].replaceAll("[^0-9]", ""));

            if (discount >= minDiscountPercentage) {
                // ---- Extração do link do jogo ----
                // No novo layout, o elemento de preço (div[role="link"]) está dentro de um <a href="/app/...">
                // Subimos até encontrar um ancestral <a> com href contendo /app/
                String gameLink = null;
                Element ancestor = priceEl.parent();
                for (int i = 0; i < 15 && ancestor != null; i++) {
                    if ("a".equals(ancestor.tagName()) && ancestor.attr("href").contains("/app/")) {
                        gameLink = ancestor.attr("href").split("\\?")[0];
                        break;
                    }
                    ancestor = ancestor.parent();
                }

                if (gameLink == null) {
                    log.debug("Link do jogo não encontrado para elemento: {}", label);
                    return;
                }

                // Se já processamos este link, ignoramos para não repetir
                if (uniqueGames.containsKey(gameLink)) return;

                // ---- Extração do título ----
                // O título está em outro <a href="/app/..."> com o mesmo appId, fora do elemento de preço
                // Buscamos a partir de um container ancestral mais amplo
                String appId = extractAppId(gameLink);
                String title = "";
                Element searchRoot = ancestor; // começa do <a> do preço
                for (int i = 0; i < 10 && searchRoot != null; i++) {
                    searchRoot = searchRoot.parent();
                    // Procura qualquer <a> que aponte para o mesmo /app/XXXXX/ e tenha texto
                    for (Element a : searchRoot.select("a[href*='/app/" + appId + "/']")) {
                        if (a.equals(ancestor)) continue; // pula o próprio link de preço
                        String text = a.text().trim();
                        // Deve ter texto, não ser o mesmo elemento de preço e não conter somente números/símbolos
                        if (!text.isEmpty() && !text.equals(a.attr("href")) && text.matches(".*[a-zA-ZÀ-ÿ].*")) {
                            title = text;
                            break;
                        }
                    }
                    if (!title.isEmpty()) break;
                }

                if (title.isEmpty()) {
                    log.warn("Título não encontrado para: {} ({})", gameLink, label);
                    title = gameLink; // fallback: usa o próprio link
                }

                // ---- Extração dos preços ----
                // Novo formato: "75% de desconto. Preço normal: R$169,00. Com desconto: R$42,25."
                // Formato antigo: "Preço normal: R$169,00. Com desconto: R$42,25."
                String originalPrice = "---";
                String currentPrice = "---";

                if (label.contains("Preço normal:") && label.contains("Com desconto:")) {
                    originalPrice = label.split("Preço normal:")[1].split("Com desconto:")[0].trim()
                            .replaceAll("\\.$", "").trim(); // remove ponto final se houver
                    currentPrice = label.split("Com desconto:")[1].trim()
                            .replaceAll("\\.$", "").trim(); // remove ponto final se houver
                }

                uniqueGames.put(gameLink, new Game(title, currentPrice, originalPrice, discount, gameLink, ""));
                log.info("Jogo encontrado: {} | {}% | {} -> {}", title, discount, originalPrice, currentPrice);
            }
        } catch (Exception e) {
            log.debug("Erro ao processar elemento de preço: {}", e.getMessage());
        }
    }

    /**
     * Extrai o AppID numérico de uma URL Steam como /app/1196590/Resident_Evil_Village
     */
    private String extractAppId(String url) {
        // ex: https://store.steampowered.com/app/1196590/Resident_Evil_Village
        String[] parts = url.split("/app/");
        if (parts.length > 1) {
            return parts[1].split("/")[0];
        }
        return "";
    }

    private void performScroll(WebDriver driver) {
        try {
            // Realiza scroll gradual para carregamento lazy de elementos
            // Steam carrega elementos sob demanda, portanto precisamos de mais tempo
            for (int i = 0; i < 10; i++) {
                ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
                Thread.sleep(1500);
            }
            // Aguarda um pouco mais para garantir que todos os elementos foram carregados
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String generatePlainTextEmail(List<Game> games) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < games.size(); i++) {
            Game game = games.get(i);
            String discountStr = game.getDiscountPercentage() + "%";

            String rawPrice = game.getCurrentPrice();
            String numberOnly = "0"; // Valor padrão caso venha nulo

            if (rawPrice != null) {
                numberOnly = rawPrice
                        .replace("CLP", "")
                        .replace("R$", "")      // Remove prefixo BRL
                        .replace("$", "")       // Remove o cifrão para alinhar nós mesmos
                        .replaceAll("\\.$", "") // Remove ponto se estiver no final
                        .trim();
            }

            String line = String.format("%2d %7s %-4s %.30s",
                    i + 1,
                    numberOnly,
                    discountStr,
                    game.getTitle()
            );
            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    private String getLastLogContent() {
        try {
            Path dir = Paths.get(LOG_DIR);
            if (!Files.exists(dir)) return "";

            try (Stream<Path> stream = Files.list(dir)) {
                Optional<Path> lastLog = stream
                        .filter(f -> !Files.isDirectory(f))
                        .filter(f -> f.getFileName().toString().startsWith("log_") && f.toString().endsWith(".txt"))
                        .max(Comparator.comparing(Path::getFileName));

                if (lastLog.isPresent()) {
                    log.info("📂 Último log carregado para comparação: " + lastLog.get().getFileName());
                    return Files.readString(lastLog.get(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            log.error("Erro ao ler último log", e);
        }
        return "";
    }

    private void saveLogFile(String content) {
        try {
            Path logDir = Paths.get(LOG_DIR);
            createLogDirectoryIfNotExists(logDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "log_" + timestamp + ".txt";
            Path filePath = logDir.resolve(fileName);

            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.info("Log salvo em: " + filePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Erro ao salvar log", e);
        }
    }

    private void createLogDirectoryIfNotExists(Path dirPath) throws IOException {
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
    }

    private void saveDebugHtml(String html) {
        try {
            Files.createDirectories(Paths.get(LOG_DIR));
            Files.writeString(Paths.get(LOG_DIR, "debug_source.html"), html);
        } catch (IOException e) {
        }
    }

    private void sendEmail(String to, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setFrom(emailSender);
            helper.setText(body, false);
            mailSender.send(message);
            log.info("E-mail enviado!");
        } catch (MessagingException e) {
            log.error("Erro e-mail", e);
        }
    }
}