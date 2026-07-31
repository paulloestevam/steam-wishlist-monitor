# Correção de Rate Limiting do Steam

## Problema
O Steam estava retornando o erro:
```
"Você realizou solicitações demais recentemente. Aguarde e tente realizar a sua solicitação novamente mais tarde."
```

Este é um erro de rate limiting - o Steam limita a quantidade de requisições que pode receber em um curto período de tempo para proteger seus servidores.

## Solução Implementada

### 1. **Retry Automático com Backoff Exponencial**
- Detecta automaticamente quando o Steam retorna um erro de rate limiting
- Aguarda e tenta novamente com espera progressiva (10s → 20s → 40s)
- Máximo de 3 tentativas por URL

### 2. **Delays Entre Requisições**
- Adicionado delay de **20 segundos** entre o acesso a diferentes wishlists
- Aumenta gradualmente o tempo entre requisições para simular comportamento humano
- Configurável no arquivo `application.yaml` via parâmetro `steam.delay-between-requests-ms`

### 3. **Scroll Melhorado**
- Aumentado o número de scrolls de 8 para 10
- Reduzido delay entre scrolls de 2000ms para 1500ms (melhor distribuição)
- Adicionado espera extra de 3 segundos após scroll para carregamento completo
- Total: ~18 segundos de scroll para garantir carregamento de todos os elementos

### 4. **Headers Mais Realistas**
- User-Agent atualizado para parecer um navegador Chrome moderno
- Adicionados flags do Chrome:
  - `--disable-blink-features=AutomationControlled`: Oculta identificação de automação
  - `--disable-gpu`: Desativa GPU para melhor estabilidade
  - `--disable-web-resources`: Reduz consumo
- Linguagem definida como "pt-BR,pt;q=0.9,en;q=0.8"

### 5. **Detecção de Erro**
O código agora detecta vários padrões de erro de rate limiting:
- "Você realizou solicitações demais recentemente"
- "solicitações demais"
- "HTTP 429"
- "Too Many Requests"

## Fluxo de Execução

```
1. Acessa primeira wishlist
   ↓
2. Detecta erro de rate limiting?
   ├─ SIM: Aguarda 10s e tenta novamente
   │       └─ Falha novamente? Aguarda 20s e tenta (máx 3 tentativas)
   └─ NÃO: Processa a página
   ↓
3. Aguarda 20s antes da próxima wishlist
   ↓
4. Repete para próximas wishlists
```

## Configuração

No arquivo `application.yaml`, você pode ajustar:

```yaml
steam:
  urls:
    - "https://store.steampowered.com/wishlist/id/seu_id/?sort=discount"
  delay-between-requests-ms: 20000     # Delay entre requisições
  max-retries: 3                        # Máximo de tentativas
  retry-base-wait-ms: 10000             # Espera base para retry

webdriver:
  chrome:
    page-load-timeout-seconds: 30       # Timeout de carregamento
    implicit-wait-seconds: 15            # Espera implícita
```

## Recomendações

1. **Não reduza o delay de 20 segundos** - Quanto menor o delay, maior o risco de rate limiting
2. **Se continuar recebendo erros:**
   - Aumente `delay-between-requests-ms` para 30000 ou 40000
   - Execute em horários fora de pico (madrugada, madrugada)
   - Considere usar múltiplas contas Steam
3. **Logs:**
   - Verifique os logs em `logs/console.log` para detalhes das tentativas
   - Arquivo HTML de debug é salvo em `logs/debug_source.html`

## Exemplo de Logs com Sucesso

```
INFO  | Acessando wishlist (1/1): https://store.steampowered.com/wishlist/id/paulloestevam/?sort=discount
INFO  | ⏳ Aguardando 20 segundos antes da próxima requisição (prevenção de rate limiting)...
INFO  | ✅ Wishlist processada com sucesso!
INFO  | Total de ofertas: 15
INFO  | 15 novas ofertas detectadas! Enviando e-mail...
INFO  | E-mail enviado!
```

## Exemplo de Logs com Retry

```
INFO  | Acessando wishlist (1/1): https://store.steampowered.com/wishlist/id/paulloestevam/?sort=discount
WARN  | ❌ Rate limit detectado! Tentativa 1/3. Aguardando 10 segundos...
INFO  | ✅ Wishlist processada com sucesso!
```

## Mudanças de Código

- **SteamService.java**:
  - Novo método `fetchWishlistWithRetry()` - implementa retry com backoff exponencial
  - Novo método `isRateLimitError()` - detecta erros de rate limiting
  - Melhorias no método `performScroll()`
  - Adição de delays entre requisições
  - Melhor tratamento de exceções

- **application.yaml**:
  - Novos parâmetros de configuração
  - Documentação dos limites recomendados

## Versão

Versão da correção: 1.0
Data: 2026-07-30

