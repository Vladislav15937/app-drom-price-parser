# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## О проекте

**drom-price-parser** — Spring Boot приложение для анализа конкурентных цен на автозапчасти с baza.drom.ru. Парсит объявления по OEM-номеру через Playwright, классифицирует конкурентов через DeepSeek LLM и рекомендует конкурентную цену.

## Сборка и запуск

```bash
mvn compile
mvn spring-boot:run
```

- Swagger UI: http://localhost:8081/swagger-ui.html
- Порт: 8081
- Требуется Java 22

## Архитектура

```
PriceAggregatorController  (REST)
        ↓
PriceAnalyzer              (оркестрация + статистика)
    ├─ DromParser           (парсинг через Playwright)
    └─ AIPriceAdvisor       (vision-агент + классификатор + математика цен)
```

### DromParser
- Headless Chromium через Microsoft Playwright
- Один браузер на всё приложение (`@PostConstruct`/`@PreDestroy`), отдельный `BrowserContext` на каждый запрос
- Антибот: ротация User-Agent (5 штук), скрытие `navigator.webdriver`, задержки 1.5–3.5 с
- `parseMyListing(url)` → `MyListingInfo` (моё объявление: описание, дата, фото, цена)
- `parseParts(oem, region)` → до 10 объявлений конкурентов в городе
- `parseSiberia(oem, myCity)` → до 3 объявлений из каждого из 6 сибирских регионов (исключая мой город)
- Извлечение фото: `data-image-info` JSON атрибут → `static.baza.drom.ru` img → og:image. Фото на baza.drom.ru **без расширений** в URL — не фильтровать по `*.jpg`.
- Дата публикации: селектор `.viewbull-actual-date`
- При CAPTCHA в headless-режиме возвращает пустой список; в GUI-режиме ждёт ручного решения 120 с

### AIPriceAdvisor
API: конфиг `deepseek.api.base-url` (текущий: `https://open.blackroute.space/v1/chat/completions`), модель — `deepseek.text.model` (текущий: `deepseek-chat`).

**Vision-оценка фото отключена** — DeepSeek V4 не поддерживает изображения. Всегда возвращает нейтральный коэф. 0.80. Резервная реализация сохранена в `evaluatePhotosViaApi()`.

**Классификатор** (`classifyCompetitors`): сравнивает конкурентов с моим товаром, делит на `similar/better/worse`. Принимает только узлы в сборе — ремкомплекты и компоненты уже отфильтрованы в `PriceAnalyzer.filterAssemblies()`.

**Математика** (`computeCompetitivePrice`):
- Свежее объявление + город → работаем с городскими данными
- Старое объявление (>6 мес.) ИЛИ мало конкурентов в городе (<3) → используем Сибирь
- `similar` есть и мин. аналог дешевле → `min(similar) × 0.97` (обязательное снижение)
- `similar` есть и мы уже дешевле → не менять
- Только `worse` → `min(worse) × 1.20`
- Только `better` → `min(better) × 0.80`
- Нижний порог: `min(market) × 0.80`. Верхнего потолка нет.
- Фото-дисконт: если коэф. моего фото < 0.70 → масштабируем цену пропорционально

### Ключевые DTO
- `MyListingInfo` — моё объявление (title, description, condition, manufacturer, oem, city, publishedDate, photoUrls, price)
- `PartPrice` — объявление конкурента (price, url, location, dealer, publishedDate, photoUrls, description)
- `AggregationResult` — итог: статистика (min/max/avg/median), cityCompetitorCount, siberiaCompetitorCount, recommendedPrice, aiReason, photoNote

### Логика Сибири (PriceAnalyzer)
- `CITY_ANALOG_THRESHOLD = 3` — если в городе меньше → ищем Сибирь
- `SIBERIA_REGIONS`: barnaul, novosibirsk, omsk, tomsk, kemerovo, krasnoyarsk
- `isOlderThan6Months()` — парсит русские форматы дат: "вчера"/"сегодня"/"назад" → false; "15 ноября 2024" и "dd.MM.yyyy" → сравниваем с порогом

## Конфигурация (`application.yml`)

| Параметр | Назначение |
|---|---|
| `drom.headless` | `false` — видимый браузер для отладки, `true` для продакшена |
| `deepseek.api.token` | Ключ DeepSeek API |
| `deepseek.api.base-url` | URL API (default: `https://api.deepseek.com/v1/chat/completions`) |
| `deepseek.text.model` | Текстовая модель (default: `deepseek-v4-flash`) |
| `drom.proxy.url` | Прокси для парсера (опционально) |

## Точки расширения

- **Регионы Сибири** → `DromParser.SIBERIA_REGIONS`
- **Порог мало конкурентов** → `PriceAnalyzer.CITY_ANALOG_THRESHOLD`
- **Фильтр нецелевых товаров** → `PriceAnalyzer.NON_ASSEMBLY_KEYWORDS`
- **Процент подрезания** → `0.97` в `AIPriceAdvisor.strategyForMarket()`
- **Лимит объявлений на регион** → `parseParts(oemNumber, region, 10)` в `parseParts()`
- **CSS-селекторы парсера** → `DromParser.parseDetailPage()` и `parseMyListing()`
