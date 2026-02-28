# TextHarvester

JavaFX‑приложение для парсинга текстовых транскриптов роликов с сайта `oper.ru` и сохранения их в файлы `nameFile.txt`.

## Требования
- Java 21+
- Maven 3.8+

## Быстрый старт
1. Настройте `config.yaml` в корне проекта.
2. Запустите приложение:

```bash
mvn javafx:run
```

## Структура проекта
- `config.yaml` — основной файл настроек
- `out-files/` — папка для результатов
- `src/main/java/...` — исходники

## Настройки (`config.yaml`)

```yaml
app:
  modes:
    - single
    - list
    - build-site-list
  defaultMode: single
  singlePageUrl: "https://oper.ru/video/"
  listPageUrls:
    - "https://oper.ru/video/"
  outputDir: "out-files"
  maxItems: 0
  userAgent: "TextHarvesterBot/0.1 (+https://github.com/alexmnv01/TextHarvester)"
  timeoutSeconds: 15
  dryRun: false
```

### Описание полей
- `modes` — список режимов, которые будут отображаться в UI.
- `defaultMode` — режим по умолчанию при запуске.
- `singlePageUrl` — страница со списком роликов (для режима `single` и `build-site-list`).
- `listPageUrls` — список страниц со списками роликов (для режима `list`).
- `outputDir` — каталог для сохранения результатов.
- `maxItems` — лимит количества роликов для обработки. `0` = без ограничения.
- `userAgent` — значение `User-Agent` для HTTP-запросов.
- `timeoutSeconds` — таймаут HTTP-запросов в секундах.
- `dryRun` — если `true`, файлы не сохраняются (только проверка извлечения).

## Режимы работы

### `single`
- Загружает одну страницу (`singlePageUrl`).
- Собирает ссылки на страницы роликов.
- Для каждой ссылки извлекает транскрипт и сохраняет в `outputDir`.

### `list`
- Обходит все страницы из `listPageUrls`.
- Собирает все ссылки, удаляет дубликаты.
- Извлекает транскрипт по каждой ссылке.

### `build-site-list`
- Пытается собрать список страниц пагинации с `singlePageUrl`.
- Сохраняет список в `outputDir/site-list.txt`.

## Интерфейс
- Выбор режима из `modes` (берется из `config.yaml`).
- Список страниц (из `listPageUrls`).
- Кнопки `Start` и `Stop`.
- Статус выполнения, текущий URL, счетчики `Processed / Saved`.
- Лог‑панель внизу для всех событий.

## Формат сохранения
- Имя файла берется из названия ролика.
- Если такой файл уже существует, добавляется суффикс с датой и временем (`yyyyMMdd_HHmmss`).
- Файлы сохраняются в `outputDir`.

## Логирование
- Все события пишутся в консоль и дублируются в нижнем окне приложения.
- Используется `Lombok + slf4j`.

## Диагностика
- Если транскрипт не найден, в лог пишется URL и заголовок ролика.
- Для быстрой проверки используйте `dryRun: true` и `maxItems: 1`.

## Сборка

```bash
mvn clean package
```

## Запуск из JAR
После сборки будет создан файл:
- `target/text-harvester-0.1.0-SNAPSHOT-all.jar`

Запуск:
```bash
java -jar target/text-harvester-0.1.0-SNAPSHOT-all.jar
```

Примечание: для запуска требуется установленная Java 21 с поддержкой JavaFX.

## Запуск без установки JavaFX (Windows, jlink)
Собираем runtime‑image, который можно запускать в любом месте на Windows:

```bash
mvn javafx:jlink
```

Результат:
- `target/TextHarvester` (папка с runtime)
- запуск: `target/TextHarvester/bin/TextHarvester`

Конфигурация:
- положите `config.yaml` рядом с папкой `bin` (то есть в `target/TextHarvester/`),  
  либо запускайте из каталога, где лежит `config.yaml`.

Если нужен архив:
```bash
mvn javafx:jlink-zip
```

## Запуск (GUI)

```bash
mvn javafx:run
```

## Запуск с установленным JavaFX (Windows)
Если JavaFX SDK установлен отдельно, укажите его путь:

```bash
mvn javafx:run -Djavafx.sdk=C:\Development\javafx-sdk-21.0.10
```

Запуск jar с JavaFX SDK:
```bash
java --module-path C:\Development\javafx-sdk-21.0.10\lib --add-modules javafx.controls -jar target/text-harvester-0.1.0-SNAPSHOT-all.jar
```
