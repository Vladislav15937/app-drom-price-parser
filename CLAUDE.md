# CLAUDE.md

Гайд для Claude Code (claude.ai/code) и любого, кто работает с этим репозиторием. Описывает каждый аспект проекта: архитектуру, поток данных, ИИ-логику, антибот-механику, отчётность, конфигурацию и эксплуатационные реалии.

## О проекте

**drom-price-parser** — Spring Boot приложение для конкурентного анализа цен на автозапчасти (тормозные суппорты) с baza.drom.ru. По OEM-номеру из каталога находит наше объявление (компания **YARD86**), парсит конкурентов в двух регионах через Playwright, оценивает фото и классифицирует конкурентов через ИИ и рекомендует конкурентную цену. Итог — Excel-отчёт с рекомендацией и обоснованием по каждой детали.

Два способа использования из одного процесса:
- **Десктоп (основной рабочий режим)** — Swing-окно (`MainWindow`), пакетный прогон каталога на пуле IP с записью Excel.
- **Веб (REST + SSE)** — `PriceAggregatorController` на :8081 для одиночного/потокового анализа, Swagger UI.

## Сборка и запуск

```bash
mvn compile
mvn spring-boot:run
```

- Стек: **Spring Boot 3.3.0**, **Java 21** (`<java.version>21</java.version>` в pom; собирается и на новее), **Playwright 1.44**, **Apache POI 5.2.5** (xlsx), **FlatLaf** (тёмная тема Swing), springdoc/OpenAPI.
- Порт: **8081**. Swagger UI: http://localhost:8081/swagger-ui.html
- `Main` стартует с `setHeadless(false)` → поднимается И веб-сервер, И десктоп-окно: `DesktopUILauncher` на `ApplicationReadyEvent` запускает `TunnelService` (публичный URL) и открывает `MainWindow`.
- Если в окружении нет `mvn`/`mvnw` — используется бандл IntelliJ: `/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn -q -o compile`.

## Архитектура

```
                         ┌─ PriceAggregatorController (REST/SSE, веб)
точка входа ─────────────┤
                         └─ MainWindow (Swing, десктоп-батч)
                                   ↓
                         PriceAnalyzer  (оркестрация + статистика + кэш + повтор-OEM)
                            ├─ DromParser           (Playwright: моё объявление + конкуренты)
                            │     └─ DromParserPool  (N дорожек = N IP для параллельного батча)
                            │           └─ MobileProxyService (change-IP ссылки для ротации)
                            └─ AIPriceAdvisor        (vision-оценка фото + классификатор + математика цены)
                                   ↓
                         ExcelReport (POI, построчная запись .xlsx)
```

### Поток одной детали (`PriceAnalyzer.analyzeFromCatalogOnce`)
1. **Город**: одна загрузка страницы поиска `parseCityWithMyListing(OEM, region, 5, company, catalogPrice)` → URL нашего объявления (по компании+цене) + до 5 дешёвых конкурентов. Результат кэшируется на батч (см. кэш ниже).
2. Если наше объявление не найдено → `aiConfidence="нет данных"`, выход.
3. `parseMyListing(url)` → полная карточка нашего объявления (описание, состояние, производитель, фото, цена). Если цена каталога расходится с живой >15% — WARN (возможна уценка), в расчёте используется каталожная.
4. **НСК**: `parseParts(OEM, "novosibirsk", 5)` → до 5 дешёвых конкурентов (кэш на батч).
5. `AIPriceAdvisor.analyze(...)` → рекомендация (цена + уверенность + причина + заметка о фото).
6. Статистика по объединённому рынку (город+НСК): min/max/avg/median → `AggregationResult`.

## DromParser (1385 строк — ядро парсинга)

- **Headless Chromium** через Microsoft Playwright. Два конструктора: Spring-бин (одиночная дорожка для веб/одиночного анализа, сессия `drom-session.json`) и ручной (для дорожек пула: свой прокси, своя сессия `drom-session-{i}.json`, своё имя `L{i}`, своя change-IP ссылка).
- Один браузер на дорожку (`init()`/`destroy()`), отдельный `BrowserContext` на запрос.
- **Антибот**: фиксированный User-Agent на весь сеанс дорожки (случайный из 5 при старте), скрытие `navigator.webdriver`, задержки. Контекст блокирует загрузку картинок/CSS/шрифтов/медиа (`ctx.route`) — фото берём из HTML-атрибутов, не из сети.
- **Ключевые методы**:
  - `parseMyListing(url)` → `MyListingInfo`.
  - `parseParts(oem, region[, limit][, excludeUrls])` → конкуренты (по умолчанию 10, в батче 5).
  - `findMyListingUrl(oem, region, company)` → URL нашего объявления по имени компании на странице поиска.
  - `parseCityWithMyListing(...)` → `CityParseResult(myListingUrl, competitors, myCandidates)` за ОДНУ загрузку страницы поиска (заменяет `findMyListingUrl`+`parseParts` в каталожном пути).
  - `parsePartsBackground(...)` — НСК через отдельную сессию `drom-session-nsk.json`.
  - Записи: `CityParseResult`, `MyListingCandidate(url, price)`, `SearchEntry`.
- **Извлечение фото** (без расширений в URL — нельзя фильтровать по `*.jpg`): `data-image-info` JSON → `static.baza.drom.ru` img → `og:image`.
- **Дата публикации**: селектор `.viewbull-actual-date`.
- `detailCache` — общий (static) кэш карточек на все дорожки, TTL 30 мин.
- **Константы**: `NAV_TIMEOUT_MS=30_000`, `DEFAULT_DETAIL_LIMIT=6`, `PROXY_DOWN_THRESHOLD=5`, `CAPTCHA_BLOCK_THRESHOLD=3`, `MAX_ROTATIONS_NO_PROGRESS=4`, `ROTATE_APPLY_WAIT_MS=8000`.

### Капча и навигация
- **Капча drom — собственный чекбокс на `/verify`** (`label:has-text('Я не робот')` → `input[type='submit']`, ждём ухода с `/verify`). Это НЕ reCAPTCHA, поэтому 2captcha бесполезен (см. ниже). Решается кликом `tryClickDromCheckbox()`; если CSS не сработал → JS-клик.
- `navigateWithRetry`: тайм-аут 30 с вместо 90. `ERR_TUNNEL_CONNECTION_FAILED` (мобильный прокси «мигает») повторяется до 3 раз — поглощает кратковременные провалы IP. **Стойкий non-tunnel тайм-аут** (залип IP) → один раз `rotateIp()` и одна доп. попытка на свежем IP.
- **Реактивная ротация IP** (`noteCaptchaBlocked()` → `rotateIp()`): при нерешаемой капче дорожка крутит IP мобильного прокси через change-IP ссылку (фолбэк-хосты `aproxy.site`, `81.200.155.214`), удаляет сессию (куки привязаны к старому IP), ждёт `ROTATE_APPLY_WAIT_MS`. Жёсткий стоп дорожки — только после `MAX_ROTATIONS_NO_PROGRESS` ротаций без единой успешной страницы. Любая успешная загрузка (`proxyOk()`) сбрасывает счётчики.
- `rotationCount()` — монотонный счётчик удачных смен IP; на нём построен повтор-OEM в `PriceAnalyzer`.

## DromParserPool / MobileProxyService

- `DromParserPool` поднимает по дорожке на каждый прокси из `drom.proxies` (пусто → одна дорожка из `drom.proxy.url`). Дорожка = свой Playwright+браузер + свой IP + своя сессия + свои предохранители.
- На старте `MobileProxyService.changeIpUrlsByPort()` дёргает `GET mobileproxy.space/api.html?command=get_my_proxy` (Bearer-токен) → карта `порт → change-IP ссылка`. Пул сопоставляет дорожку со ссылкой по порту из URL прокси. Лог: «получено change-IP ссылок: N», у дорожки «ротация IP: вкл/выкл».
- Если `mobileproxy.api.token` пуст → ротация выключена, старое поведение (стоп по `CAPTCHA_BLOCK_THRESHOLD`).

## AIPriceAdvisor (963 строки — ИИ + математика цены)

Единый шлюз `open.blackroute.space` обслуживает И текст (DeepSeek), И vision (Gemini). Шлюз чувствителен к **конкурентности** запросов → общий `Semaphore gatewayLimiter` (`GATEWAY_MAX_CONCURRENT=2`, fair) держит число одновременных обращений по обоим маршрутам. `photoExecutor` — пул из 3 потоков. Всё в одном Spring-бине → лимиты глобальные на все дорожки. `temperature=0` везде.

### Агент 1 — Vision-оценка фото (Gemini)
- Активен только при непустом `gemini.api.key`; иначе нейтральный коэф. **0.80** без обращения к API.
- **Селективно** (`evaluatePhotosSelective`): vision гоняется только для конкурентов с ценой ≤ моей × 1.10 и с фото — экономия. Необоценённым/без фото ставится нейтральный коэф. = коэф. моих фото (разница 0.0 → не даёт ложный «better»). Пустые фото = деталь не догрузилась, а НЕ «продавец без фото» — не штрафуем.
- Возвращает `{condition, defects, coefficient 0.5–1.0}`. До 2 фото на деталь (base64), `max_tokens=2500`.

### Агент 2 — Попарный классификатор конкурентов (DeepSeek text)
- Для каждого конкурента строит попарное сравнение с нашим объявлением (описание, производитель, состояние, и фото — если vision активен и **наше** фото оценилось; иначе сравнение по тексту).
- **Детерминированные правила перебивают LLM** (`forceWorse[]` → строго `worse`):
  - **Аналог против нашего оригинала** (`ANALOG_TOKENS`: febest, masterkit, lynx, nagamochi, masuma, narichin, trust auto, sat-st, tabc/tabp, ta-tab).
  - **Другая сторона/позиция** (левый/правый, передний/задний — `isSideMismatch`/`isPositionMismatch`).
- **Фото сравнивается по КАТЕГОРИИ состояния** (`conditionRank`: отличн/хорош=2, удовлетвор=1, плох=0), а не по сырому коэффициенту: 0.88 vs 0.90 — одна категория, это `similar`, а не `better/worse` (разница коэф. часто = качество съёмки).
- **Кэш классификации** по набору конкурентов (ключ: моё объявление + отсортированные id конкурентов + режим vision, TTL 30 мин) → один и тот же набор даёт один вердикт → одну цену (детерминизм). Кэшируется только непустой результат.
- Принимает только узлы в сборе — ремкомплекты/компоненты отфильтрованы в `PriceAnalyzer.filterAssemblies()`.

### Математика цены (`computeCompetitivePrice` → `strategyForMarket`)
Единый рынок = до 5 дешёвых Барнаул + до 5 дешёвых НСК. Цель — попасть в рынок и продать (без надбавок над рынком). `FAST_SALE_FACTOR=0.95`, `MIN_RELIABLE_COMPETITORS=3`.

| Ситуация | Цена |
|---|---|
| `similar` есть, мы лучше большинства (`worse > better`) | **медиана аналогов** (на рынке, без надбавки) |
| `similar` есть, иначе | **медиана аналогов × 0.95** (−5%, уклон в быструю продажу), не ниже `min(similar)` |
| только `worse` (аналогов нет) | `min(worse) × 1.20` (премия) |
| только `better` (аналогов нет) | `min(better) × 0.80` (скидка) |
| аналогов/конкурентов нет | цена не меняется |
| смешанный (есть better и worse, нет similar) | `медиана × (1 + 0.15·net)`, net=(worse−better)/(worse+better) ∈ [−1;+1] |

- **Фото-дисконт** (`applyPhotoDiscount`): коэф. моих фото < 0.70 → масштаб цены пропорционально (до −30%).
- **Границы** (`applyBounds`): потолок = `max` рынка; пол = `min × 0.80`; если мы уже на минимуме рынка — не подрезаем себя ниже.
- **Тонкие данные**: сопоставимых (`reliableCount`) < 3 → уверенность «низкая» + флаг `[мало сопоставимых данных (N): … проверить вручную]`. Рекомендация НЕ подгоняется под текущую цену (иначе аудит порочно круговой) — остаётся оценка по рынку в коридоре `applyBounds`.
- **Уверенность**: «высокая» если total≥4 в similar-ветках, «средняя»/«низкая» иначе; смешанный рынок и тонкие данные → «низкая».

### Устойчивость к сбоям шлюза
- **Троттл** (HTTP 429 ИЛИ тело с «too many/concurrent/rate limit» при HTTP 200) НЕ сдаём — ждём с эскалацией (1.2→…→15 с + джиттер), сколько нужно: потеря классификации = ложное «аналогов нет».
- **Реальные** ошибки (битый/пустой ответ, сеть) ограничены: текст `TEXT_MAX_HARD_ERRORS=6`, vision `VISION_MAX_CONTENT_ERRORS=3` → затем нейтральный фолбэк.

## PriceAnalyzer (397 строк — оркестрация)

- `TOP_N=5` конкурентов на регион. `FALLBACK_REGION="novosibirsk"`.
- **Повтор OEM** (`MAX_OEM_ATTEMPTS=3`): `analyzeFromCatalog` обёрнут в цикл; если за прогон OEM счётчик ротаций вырос (была капча со сменой IP) → OEM прогоняется заново на свежем IP. Стоп: успех без ротации / `isCaptchaBlocked()` / лимит попыток. Работает и для пула (`analyzeCatalogLane`), и для одиночной дорожки.
- **Кэш конкурентов на батч** (`cityCache`/`nskCache`, ключ `OEM+регион`, общий на дорожки, чистится в `resetBreakers()`): дубли OEM = разные наши объявления, но рынок конкурентов один → не грузим страницу поиска повторно. Кэшируем **только успех** (наше объявление найдено / непустой рынок) — капчевую пустышку нельзя, иначе ломается повтор-на-свежем-IP. `parseMyListing` всё равно зовётся под каждую строку (своя цена).
- `filterAssemblies` — выкидывает не-узлы (`NON_ASSEMBLY_KEYWORDS`: ремкомплект, поршень, направляющ, пыльник, скоба и т.д.) и объявления с НЕ совпадающим OEM.
- `pickUrlByPrice` — выбор нашего объявления из кандидатов по близости цены к каталожной (точное — приоритет).
- `isOlderThan6Months` — парсит русские форматы дат: «вчера»/«сегодня»/«назад» → false; «15 ноября 2024» и «dd.MM.yyyy» → сравнение с порогом 6 мес.
- Предохранители: `isProxyDown(lane)`, `isCaptchaBlocked(lane)`, `laneCount()`, `resetBreakers()`.

### Логика двух регионов (только Барнаул + Новосибирск)
- Анализ ВСЕГДА идёт по двум регионам: город (мой) + НСК как ориентир. Отдельной сложной «логики Сибири» нет — только эти два города.
- `FALLBACK_REGION=novosibirsk`. Если мой регион уже НСК — второй регион не запрашивается.

## Десктоп-батч (`MainWindow`, 1032 строки)

- Загрузка CSV-каталога → таблица 1:1 с `catalogRows` (индексы строк не разъезжаются).
- **Дедупа по OEM НЕТ**: дубли OEM — это разные наши объявления (один OEM, разные качество/цена); деталь = компания(YARD86)+OEM+цена.
- **Формат CSV каталога**: Windows-1251, разделитель `;`. Колонки (0-idx): 0 Номер товара, 1 Запчасть, 2 Цена, 3 Номер производителя (=OEM), 4 Ткацкая(свободно, остаток), 5-6 Ткацкая, 7-9 «54 YARD». Загрузка берёт только узлы в сборе и `Ткацкая(свободно)>0`. Марок/моделей авто в формате нет → колонка «Авто» пустая.
- `runBatchAnalysis`: диспетчер в фоновом потоке поднимает `lanePool` из `laneCount()` воркеров (1 IP на воркера), задания раздаются через общий курсор (`AtomicInteger`). **Предохранитель на каждый IP отдельно**: сбойная дорожка останавливается, остальные работают.
- После анализа КАЖДОЙ детали строка дописывается в `.xlsx` и файл пересохраняется (`ExcelReport.append` + `save`, потокобезопасно) — данные не теряются при обрыве/капче.
- Кнопка Stop (`batchStopped`), статус «Готово N / M (дорожек: K)».

### Excel-отчёт (`ExcelReport`, 172 строки)
Файл `price-report_<дата>.xlsx` в рабочей папке, 13 колонок:
`№, OEM, Запчасть, Авто, Цена на сайте, Рекоменд., Δ к рынку %, Состояние, Уверенность, Город, НСК, Ссылка на объявление, Причина`.
- «Цена на сайте» и «Ссылка» — живые из `AggregationResult.myListingPrice/myListingUrl`; ссылка кликабельная (Hyperlink). «Авто» в новом формате каталога пустая.
- **Δ к рынку % = (цена на сайте − рекомендованная) / рекомендованная.** Та же величина определяет «Состояние» и цвет строки:
  - **зелёный** «совпадает» — |Δ| ≤ 2% (допуск `createReport()` = 0.02)
  - **красный** «выше рынка» — цена на сайте ВЫШЕ рекомендации
  - **синий** «ниже рынка» — НИЖЕ рекомендации
  - **серый** «нет данных» / «цена не указана»
- Шапка заморожена (`createFreezePane`).

## REST API (`PriceAggregatorController`, :8081/api/v1)
- `GET /info` — local/public URL (туннель).
- `POST /analyze/stream` (SSE) — одиночный анализ.
- `POST /catalog-item/stream` (SSE) и `/catalog-item/submit` + `GET /job/{jobId}` — анализ по OEM из каталога (потоковый и polling).
- `POST /batch/stream` (SSE) — пакетный анализ.
- Задания (`jobs`) живут 30 мин, keep-alive по SSE. `TunnelService` отдаёт публичный URL.

## Ключевые DTO
- `MyListingInfo` — наше объявление: title, description, condition, manufacturer, oem, city, publishedDate, photoUrls, price.
- `PartPrice` — объявление конкурента: title, price, url, location, dealer, publishedDate, photoUrls, oem, description.
- `AggregationResult` — итог: статистика (min/max/avg/median), cityCompetitorCount, siberiaCompetitorCount, searchedSiberia, recommendedPrice, aiConfidence, aiReason, myPhotoAssessment, myListingDate/Url/Price, marketNote, items/siberiaItems, collectedAt.
- `DromListingItem`, `RunActorRequest`, `RunResponse` — DTO Apify-интеграции (`apify.enabled=false`).

## Вспомогательные сервисы
- `MobileProxyService` — change-IP ссылки по account-токену (см. выше).
- `LocalSocksProxy` — мост HTTP CONNECT → SOCKS5 (Chromium не умеет SOCKS5 с авторизацией напрямую).
- `TunnelService` — публичный URL для веб-доступа к десктоп-инстансу.
- `CaptchaSolverService` — 2captcha; **фактически не используется** (`twocaptcha.enabled=false`): капча drom без sitekey, `solve()` всегда false. Капчу проходит чекбокс + ротация IP.
- `ApifyDromService` — альтернативный парсинг через Apify-актор (`apify.enabled=false` по умолчанию; Playwright — основной путь).

## Конфигурация (`application.yml`)

| Параметр | Назначение |
|---|---|
| `drom.headless` | `true` для продакшена (пул headless-браузеров); `false` для отладки |
| `drom.proxies` | Список прокси пула через запятую (каждый = свой IP = воркер); пусто → 1 дорожка |
| `drom.proxy.url` | Прокси одиночной дорожки (веб/одиночный анализ) |
| `mobileproxy.api.token` / `.url` | Account-токен mobileproxy.space для авто-дискавери change-IP ссылок (ротация). Пусто → ротация выкл |
| `gemini.api.key` | Ключ Gemini для vision (пусто → vision выключен, нейтральный коэф.) |
| `gemini.api.base-url` / `gemini.vision.model` | Шлюз и модель vision (`gemini-2.5-flash-lite`) |
| `deepseek.api.token` / `.base-url` / `deepseek.text.model` | Ключ, шлюз и модель текста (`deepseek-chat`) |
| `twocaptcha.enabled` / `.api-key` | Выключено — на капчу drom не работает |
| `apify.enabled` | Использовать Apify вместо Playwright (default `false`) |

**Прокси (mproxy.site) имеют срок годности** — он в комментариях `application.yml` рядом с портами. Порт `21115` истекает первым (16-06-2026), остальные позже (14-07-2026). При истечении дорожка отвалится — обновить креды в `drom.proxies` и `drom.proxy.url`.

**ИИ-баланс** проверяется по шлюзу: `GET https://open.blackroute.space/key/info` с `Authorization: Bearer <ключ>` → JSON с `spend`/`max_budget` (LiteLLM). Лимит каждого ключа сейчас $10.

## Точки расширения
- **Порог мало сопоставимых** → `AIPriceAdvisor.MIN_RELIABLE_COMPETITORS` (3).
- **Уклон в быструю продажу** → `AIPriceAdvisor.FAST_SALE_FACTOR` (0.95); смешанный рынок — коэф. `0.15` в `strategyForMarket()`.
- **Премия/скидка без аналогов** → `×1.20` / `×0.80` в `strategyForMarket()`.
- **Фильтр нецелевых товаров** → `PriceAnalyzer.NON_ASSEMBLY_KEYWORDS`.
- **Бренды аналогов** → `AIPriceAdvisor.ANALOG_TOKENS`.
- **Лимит конкурентов на регион** → `PriceAnalyzer.TOP_N` (5).
- **Конкурентность шлюза** → `AIPriceAdvisor.GATEWAY_MAX_CONCURRENT` (2).
- **Тайм-ауты/ротация прокси** → `DromParser.NAV_TIMEOUT_MS`, `MAX_ROTATIONS_NO_PROGRESS`, `ROTATE_APPLY_WAIT_MS`, `CAPTCHA_BLOCK_THRESHOLD`.
- **CSS-селекторы парсера** → `DromParser` (`extractPartFromPage`, `parseMyListing`, извлечение фото/даты).
- **Допуск «совпадает»** → `0.02` в `MainWindow.createReport()`.

## Эксплуатационные реалии (выяснено на живых прогонах)

- **Пустые строки ≈ реальные пробелы рынка, не прокси.** На прогоне 255 деталей: 94% строк получили ≥3 конкурента, «оба региона = 0» — лишь ~4%, и большинство — редкие OEM, которых нет в продаже в Барнауле/НСК. Масштабирование IP их НЕ закроет. Транзитный промах прокси отличается тем, что тот же OEM нашёлся в другой строке (дубль) — его подбирает повтор-на-свежем-IP. Не записывать «нет данных» автоматически в «проблему прокси».
- **Прокси — лимит пропускной способности/времени, а не качества данных.** При больших объёмах (тысячи деталей за прогон) узкое место — капча/тайм-ауты и длительность, а не дыры в данных.
- **Стоимость ИИ**: vision (`gemini-2.5-flash-lite`) ≈ $0.0013/деталь, текст ≈ $0.0005/деталь → ~$0.0019/деталь, ~$0.33 за 255-деталь прогон. Vision — основная статья и кончается первым.
- **Главный бизнес-вывод отчётов**: ~70% объявлений YARD86 стоят выше конкурентного уровня (Состояние «выше рынка», красный) — основной материал для снижения цен. Выбросы Δ>+100% проверять вручную.
- **Распределение уверенности типичного прогона**: ~77% «высокая», ~18% «низкая» (тонкие данные/смешанный рынок). Низкие и «нет данных» — в ручную проверку, не в автоприменение.

## Память проекта
Долгоживущие факты и решения — в `/Users/vladislav/.claude/projects/-Users-vladislav-IdeaProjects-app-drom-price-parser/memory/` (индекс `MEMORY.md`): анти-бот/капча, пул прокси, реактивная ротация IP, стоимость ИИ, идентичность объявления и кэш, «пустые строки = реальные пробелы», «оставлять ИИ-оценки». Свериться там перед изменениями в парсере/прокси/ценовой логике.
