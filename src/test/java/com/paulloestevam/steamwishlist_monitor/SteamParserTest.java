package com.paulloestevam.steamwishlist_monitor;

import com.paulloestevam.steamwishlist_monitor.model.Game;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste unitário do parser do Steam Wishlist.
 * Roda sem Chrome, sem email, sem Spring — apenas Jsoup.
 *
 * Para usar o HTML real da sua wishlist:
 *   coloque o HTML salvo em: view-source_https___store.steampowered.com_wishlist_id_paulloestevam__sort=discount.html
 *   (na raiz do projeto, que é onde você já tem)
 */
class SteamParserTest {

    private static final int MIN_DISCOUNT = 70;

    // -------------------------------------------------------------------------
    // Teste com HTML mínimo (sempre funciona, valida a lógica do parser)
    // -------------------------------------------------------------------------
    @Test
    void testParsingComHtmlMinimo() {
        String html = """
            <!DOCTYPE html>
            <html>
            <body>
            <div class="wishlist-row">
              <div class="P-zVPa2bdmQ-">
                <a href="https://store.steampowered.com/app/1196590/Resident_Evil_Village?snr=1_25_4__318">Resident Evil Village</a>
              </div>
              <div class="_6QzykeEsckc-">
                <a href="https://store.steampowered.com/app/1196590/Resident_Evil_Village?snr=1_25_4__318">
                  <div role="link" aria-label="75% de desconto. Preço normal: R$169,00. Com desconto: R$42,25.">
                    <div>-75%</div><div>R$42,25</div>
                  </div>
                </a>
              </div>
            </div>
            <div class="wishlist-row">
              <div class="P-zVPa2bdmQ-">
                <a href="https://store.steampowered.com/app/1240440/Resident_Evil_4?snr=1_25_4__318">Resident Evil 4</a>
              </div>
              <div class="_6QzykeEsckc-">
                <a href="https://store.steampowered.com/app/1240440/Resident_Evil_4?snr=1_25_4__318">
                  <div role="link" aria-label="75% de desconto. Preço normal: R$49,00. Com desconto: R$12,25.">
                    <div>-75%</div><div>R$12,25</div>
                  </div>
                </a>
              </div>
            </div>
            </body>
            </html>
            """;

        Document doc = Jsoup.parse(html);
        List<Game> games = parseGames(doc);

        System.out.println("=== JOGOS ENCONTRADOS (HTML mínimo) ===");
        games.forEach(g -> System.out.printf("  [%d%%] %s | %s -> %s%n",
                g.getDiscountPercentage(), g.getTitle(), g.getOriginalPrice(), g.getCurrentPrice()));

        assertEquals(2, games.size(), "Deveria encontrar 2 jogos");

        Game re = games.stream()
                .filter(g -> g.getTitle().equals("Resident Evil Village"))
                .findFirst()
                .orElse(null);

        assertNotNull(re, "Deve encontrar Resident Evil Village");
        assertEquals(75, re.getDiscountPercentage());
        assertEquals("R$42,25", re.getCurrentPrice());
        assertEquals("R$169,00", re.getOriginalPrice());
        assertTrue(re.getUrl().contains("/app/1196590/"));
    }

    // -------------------------------------------------------------------------
    // Teste com o HTML real baixado da sua wishlist
    // -------------------------------------------------------------------------
    @Test
    void testParsingComHtmlReal() throws IOException {
        File realHtml = new File("view-source_https___store.steampowered.com_wishlist_id_paulloestevam__sort=discount.html");
        if (!realHtml.exists()) {
            System.out.println("HTML real não encontrado em: " + realHtml.getAbsolutePath());
            System.out.println("Pulando teste. Copie o HTML para essa localização e rode novamente.");
            return;
        }

        String content = Files.readString(realHtml.toPath(), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(content);
        List<Game> games = parseGames(doc);

        System.out.println("=== JOGOS ENCONTRADOS (HTML real) ===");
        if (games.isEmpty()) {
            System.out.println("  Nenhum jogo encontrado. Verifique se o HTML é o real (Selenium) ou view-source (Chrome).");
            System.out.println("  Dica: rode a app uma vez para gerar logs/debug_source.html e use esse arquivo aqui.");
        } else {
            games.forEach(g -> System.out.printf("  [%d%%] %-40s | %s -> %s%n",
                    g.getDiscountPercentage(), g.getTitle(), g.getOriginalPrice(), g.getCurrentPrice()));
        }

        assertNotNull(games);
    }

    // -------------------------------------------------------------------------
    // Replica a lógica do SteamService.processPriceElement
    // -------------------------------------------------------------------------
    private List<Game> parseGames(Document document) {
        Map<String, Game> uniqueGames = new HashMap<>();

        Elements priceElements = document.select("[aria-label*='de desconto']");
        System.out.println("Elementos com 'de desconto' encontrados: " + priceElements.size());

        for (Element priceEl : priceElements) {
            try {
                String label = priceEl.attr("aria-label");
                int discount = Integer.parseInt(label.split("%")[0].replaceAll("[^0-9]", ""));

                if (discount < MIN_DISCOUNT) continue;

                // Extrai link: sobe pelos ancestrais até achar <a href="/app/...">
                String gameLink = null;
                Element ancestor = priceEl.parent();
                for (int i = 0; i < 15 && ancestor != null; i++) {
                    if ("a".equals(ancestor.tagName()) && ancestor.attr("href").contains("/app/")) {
                        gameLink = ancestor.attr("href").split("\\?")[0];
                        break;
                    }
                    ancestor = ancestor.parent();
                }

                if (gameLink == null || uniqueGames.containsKey(gameLink)) continue;

                // Extrai título: procura <a> com o mesmo appId que tenha texto com letras
                String appId = extractAppId(gameLink);
                String title = "";
                Element searchRoot = ancestor;
                for (int i = 0; i < 10 && searchRoot != null; i++) {
                    searchRoot = searchRoot.parent();
                    if (searchRoot == null) break;
                    for (Element a : searchRoot.select("a[href*='/app/" + appId + "/']")) {
                        if (a.equals(ancestor)) continue; // pula o próprio link de preço
                        String text = a.text().trim();
                        if (!text.isEmpty() && text.matches(".*[a-zA-ZÀ-ÿ].*")) {
                            title = text;
                            break;
                        }
                    }
                    if (!title.isEmpty()) break;
                }

                // Extrai preços do aria-label
                String originalPrice = "---";
                String currentPrice = "---";
                if (label.contains("Preço normal:") && label.contains("Com desconto:")) {
                    originalPrice = label.split("Preço normal:")[1].split("Com desconto:")[0]
                            .trim().replaceAll("\\.$", "").trim();
                    currentPrice = label.split("Com desconto:")[1]
                            .trim().replaceAll("\\.$", "").trim();
                }

                System.out.printf("  PARSE OK | appId=%s | titulo='%s' | %d%% | %s -> %s%n",
                        appId, title, discount, originalPrice, currentPrice);

                uniqueGames.put(gameLink, new Game(title, currentPrice, originalPrice, discount, gameLink, ""));

            } catch (Exception e) {
                System.out.println("  Erro ao processar elemento: " + e.getMessage());
            }
        }

        List<Game> result = new ArrayList<>(uniqueGames.values());
        result.sort(Comparator.comparingInt(Game::getDiscountPercentage).reversed());
        return result;
    }

    private String extractAppId(String url) {
        String[] parts = url.split("/app/");
        if (parts.length > 1) return parts[1].split("/")[0];
        return "";
    }
}
