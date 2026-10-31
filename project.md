# project.md — рабочая памятка по Med Tracker

Этот документ — контекст для агентов/разработчиков в свежих сессиях. Источник требований — [docs/ТЕХЗАДАНИЕ.md](docs/ТЕХЗАДАНИЕ.md) (версия 1.1): всё, что здесь не описано, ищется там. Персональные данные в репозиторий не помещаются (п. 12.5 ТЗ) — это касается и этой памятки.

## 1. Что это

Android-приложение **Med Tracker** ( `ru.danil.medtracker`, minSdk 26 / targetSdk 35) — офлайн-учёт льготного лекарственного обеспечения: пациенты, назначения с нормами выдачи, журнал выдач, автоматический расчёт даты «обеспечен до», фото рецептов с офлайн-OCR, уведомления, генерация документов Word, резервные копии. Один пользователь, одно устройство, русский интерфейс, портретная ориентация.

**Железные правила:**
- разрешение `INTERNET` запрещено; данные не покидают устройство;
- допустимые разрешения: `CAMERA`, `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` (+ транзитивный `WAKE_LOCK` от WorkManager);
- реальные данные пациентов / фото рецептов / рабочие файлы — никогда в репозиторий;
- `.gitignore` уже исключает `*.mtbackup`, `*.mtdelta`, keystore-файлы, `.mimosa/`.

## 2. Репозиторий и релизы

- Репозиторий: https://github.com/rustam285/project-danil (ветка `main`).
- CI: `.github/workflows/release.yml`. На каждый пуш — юнит-тесты + сборка APK (артефакт). На тег `v*` — подписанный релизный APK в GitHub Releases.
- **Выпуск новой версии:** закоммитить в `main`, дождаться зелёного CI, поставить аннотированный тег `vX.Y.Z` и запушить его. Больше ничего вручную не нужно. `versionCode` вычисляется из тега (`v1.2.3` → 10203), `versionName` — вручную в `app/build.gradle.kts`.
- Подпись релизов: постоянный keystore. Секреты CI (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) уже установлены. Локальная сборка релиза берёт то же из `keystore.properties` (в git не попадает). **Не терять keystore и не менять его** — иначе обновления поверх установленного приложения не встанут.

## 3. Окружение на этой машине (Windows, Git Bash)

- Проект: `E:\Danil\MedTracker`. Рабочие материалы заказчика (НЕ в репозиторий): `E:\Danil\Материалы`.
- JDK 22 (Temurin), Gradle 8.10.2 (`C:\gradle\gradle-8.10.2\bin`), wrapper сгенерирован — в проекте есть `gradlew`.
- Android SDK: `C:\Users\kamir\AppData\Local\Android\Sdk` (platforms;android-35, build-tools;34.0.0). `local.properties` указывает туда — **путь только с прямыми слэшами**, в `.properties` обратные ломаются.
- Keystore: `E:\Danil\keystore\release.keystore`, alias `medtracker`, пароли — в `E:\Danil\MedTracker\keystore.properties` (gitignored).
- Сборка/тесты: `./gradlew :app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleRelease`. Перед пушем тесты должны быть зелёные.

## 4. Стек и карта кода

Kotlin 2.0.21, Jetpack Compose (Material 3), Room + KSP, DataStore, WorkManager, CameraX, Tesseract4Android (JitPack), kotlinx-serialization, Coil. Версии — в `gradle/libs.versions.toml`.

```
app/src/main/java/ru/danil/medtracker/
├── App.kt, MainActivity.kt        # вход, навигация (Routes), нижние вкладки
├── calc/
│   ├── Rational.kt                # точные дроби (BigInteger) — double запрещён в расчётах (п. 4.1 ТЗ)
│   ├── SupplyEngine.kt            # ядро «обеспечен до» по Приложению Г ТЗ
│   └── CalcService.kt             # мост Room→движок, форматирование норм, подсветка (Level)
├── data/
│   ├── db/ (Entities.kt, Daos.kt, AppDatabase.kt)   # 7 таблиц, schemaVersion=2
│   ├── Repo.kt                    # операции + UI-модели (PrescriptionView, PatientListItem, UrgentItem)
│   ├── SettingsStore.kt           # DataStore: уведомления, пороги, каталог копий, lastFullId
│   └── AppContainer.kt            # ручной DI
├── ui/
│   ├── CommonUi.kt                # DatePickerField (календарь), SupplyBadge, IntField, EmptyState
│   ├── theme/Theme.kt             # светлая/тёмная
│   └── screens/                   # Patients, Urgent, PatientCard/PatientEdit, Prescription(+Edit),
│                                  # IssueForm, RxEdit (+OCR UI), RxScan (вкладка «Рецепт»), Settings,
│                                  # CopyPrescriptions, Onboarding
├── docx/
│   ├── DocxBuilder.kt             # минимальный OOXML-генератор (ZIP вручную, без POI)
│   ├── PlanDnDoc.kt               # «План диспансерного наблюдения» (п. 8.2)
│   ├── SummaryDoc.kt              # «Сводная потребность» (п. 8.3, таблица)
│   └── DocxShare.kt               # Загрузки/MedTracker + «Поделиться»
├── ocr/
│   ├── OcrEngine.kt               # Tesseract rus+eng (модели в assets), предобработка, parse() полей
│   ├── TextMatch.kt               # сопоставление OCR-строк с пациентами/препаратами (для RxScan)
│   └── PhotoStore.kt              # фото в filesDir/photos, сжатие ≤1600px/JPEG80, EXIF-поворот
├── notify/Notify.kt               # AlarmScheduler (exact), AlarmReceiver, BootReceiver, CheckWorker,
│                                  #   DailyCheck: одно сводное InboxStyle-уведомление в день
└── backup/
    ├── BackupData.kt              # схема manifest.json/db.json (kotlinx-serialization)
    └── BackupManager.kt           # полная/быстрая копии, SHA-256, tombstones, восстановление
```

Ассеты OCR: `app/src/main/assets/rus.traineddata`, `eng.traineddata` (tessdata_fast; заменить на best = скачать файлы и заменить).

## 5. Домен — самое главное из ТЗ

- **Сущности:** Patient, Drug (справочник, `pieceNounForms` = «картридж/картриджа/картриджей» для документов), Prescription (назначение), Norm (записи нормы с `validFrom` — история не удаляется), Issue (выдача/начальный остаток, снимок `piecesPerPackAtIssue`), Rx (рецепт), Tombstone (удаления для дельта-копий).
- **Норма:** штучная часть (PER_DAY / PER_MONTH / PER_YEAR / EVERY_X_DAYS) приоритетнее МЕ-части; `noCalc` = резерв без расчёта; действует запись с наибольшим `validFrom ≤ d`.
- **Расчёт «обеспечен до»** (`SupplyEngine.supplyEnd`): запас доступен со следующего дня после выдачи; расход — точной дробью, месяцы/годы календарные; день дефицита обнуляет запас, новая выдача считается с нуля; «обеспечен до» = последний покрытый день (или дата выдачи, если сразу не хватает); горизонт 5 лет → `Unlimited`. Эталоны — Приложение А (7 кейсов) и Б, всё в `SupplyEngineTest` — **обязаны сходиться точно**.
- **Исторические даты** в журнале и плане ДН: `supplyEndAsOf` по выдачам ≤ данной (п. 4.6).
- **Начальный остаток** только по дате «обеспечен до»: `piecesNeeded` (округление вверх) → фиксируется в `totalPieces`.
- **Подсветка:** красный < сегодня; жёлтый сегодня..+N (порог лекарств); зелёный дальше. Рецепты: только «выписан», истёк вычисляется на лету.
- **Нет выдач** → «выдач нет», не «запас > 5 лет».

## 6. Текущий статус

Выпущено: **v1.0.0** (полный объём разделов 3–10 ТЗ), **v1.0.1** — правки по результатам проверки на устройстве, **v1.1.0** — OCR-фикс, вкладка «Рецепт», статус «В ожидании поступления», **v1.1.1** — починка OCR на телефонах с 16КБ-страницами:

1. Все поля дат — календарь Material 3 (`DatePickerField`), ручной ввод убран.
2. Дата льготы редактируется календарём.
3. Краш камеры рецептов — не был объявлен `cache-path camera/` у FileProvider; OCR — убрана жёсткая бинаризация (Tesseract бинаризует сам), добавлен отчёт распознавания, послаблены шаблоны срока, тесты парсера.
4. Диалог препарата — FlowRow, чипы переносятся, а не сжимаются.
5. «Выдач нет» вместо ложного «запас > 5 лет» (списки пациентов/карточка/назначение).
6. Будущая дата рождения блокируется.
7. **v1.1.0, OCR не распознавал ничего:** главная причина — EXIF-поворот не применялся (`BitmapFactory` игнорирует ориентацию, пересжатие JPEG тег стирает) → портретные снимки хранились повёрнутыми на 90°. Исправлено в `PhotoStore` (+ `androidx.exifinterface`); в `OcrEngine` добавлены апскейл до ~2600 px (~300 DPI), `PSM_AUTO` + `user_defined_dpi=300`, перебор поворотов 90/270/180 при «нечитаемом» тексте (`scoreText`), атомарное копирование traineddata, парсинг даты рождения, МКБ с кириллическими двойниками (Е10→E10). В proguard добавлены keep для `com.googlecode.tesseract/leptonica.**` (R8 ломал JNI в релизе). tessdata_best для rus отклонён — 14.6 МБ, APK вырос бы на ~11 МБ.
8. **v1.1.0, вкладка «Рецепт»** (справа от «Срочное», `RxScanScreen`): скан бланка → OCR автоматически → проверка полей → автоподбор пациента по ФИО (создание нового на месте) и назначения по препарату → сохранение рецепта, кнопка «Выдать сейчас». Сопоставление — `ocr/TextMatch.kt`.
9. **v1.1.0, «В ожидании поступления»** (`Prescription.awaitingRestock`, миграция БД v2): кнопка у закончившихся препаратов в «Срочном», отдельная секция, чип в экране назначения, маркер в карточке; любая выдача снимает статус автоматически (`Repo.saveIssue`/`saveInitialBalance`).
10. **v1.1.1, OCR не работал на новом телефоне заказчика — 16КБ-страницы.** Tesseract4Android 4.7.0 собран под 4КБ-страницы: на Android 15+/16 с 16КБ-страницами (свежие прошивки, в т.ч. Xiaomi/HyperOS) `dlopen` падает `UnsatisfiedLinkError` → краш приложения на «Распознать», у заказчика OCR «не работал» ни с камеры, ни из галереи, при полностью рабочем конвейере. Воспроизведено на эмуляторе `google_apis_ps16k` (тест падал в `UnsatisfiedLinkError: dlopen failed: empty/missing DT_HASH/DT_GNU_HASH`), починка проверена там же и на обычном 4КБ-эмуляторе. Изменения: зависимость `cz.adaptech.tesseract4android:tesseract4android:4.9.0` (новые координаты на JitPack, старые `com.github.adaptech-cz.*` кончаются на 4.8.0; у 4.9.0 нативные библиотеки выровнены на 16КБ, Tesseract 5.5.1); `OcrEngine.friendlyError()` + перехват `Throwable` в OCR-путях (`RxEdit`/`RxScan`) — нативные сбои теперь показываются диалогом, а не крашат приложение; логи `OcrEngine` (init/размер/score/повороты). Инструментальные тесты `androidTest`: инициализация движка + распознавание печатного бланка всегда, реального фото — при `adb push photo.jpg /sdcard/Android/data/<pkg>/files/ocr_probe.jpg` (личные фото в репозиторий не идут).

Тесты: юнит 35/35 (`SupplyEngineTest` — эталоны, `RationalTest`, `OcrEngineTest`, `TextMatchTest`) + 2 инструментальных (`OcrEngineInstrumentedTest`).

**Качество OCR подтверждено офлайн:** весь конвейер приложения (PhotoStore → OcrEngine, те же traineddata) воспроизведён на десктопе (`E:\Danil\ocr-lab\replicate.py`, Tesseract 5.4) на 21 реальном бланке из `E:\Danil\Материалы\Фотографии рецептов` — 21/21 распознаются (score 1400–2300, серия/№/даты вытаскиваются). Если OCR на устройстве снова «не работает» — первым делом logcat по тегу `OcrEngine`, и проверить страницы прошивки: `adb shell getconf PAGESIZE` (16384 = нужны свежие сборки библиотек ≥4.8).

**Ждёт проверки на устройстве (нужен телефон заказчика):** OCR на новом телефоне после v1.1.1 (должен заработать; если нет — взять текст ошибки из диалога/logcat), скан-поток «Рецепт», доставка уведомления 08:00 на Xiaomi/HyperOS (автозапуск), открытие .docx в Word/WPS.

## 7. Грабли, на которые уже наступали

- **Tesseract4Android**: артефакт только на JitPack — `com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.7.0` (репозиторий в `settings.gradle.kts`), а классы живут в пакете **`com.googlecode.tesseract.android`**, не `cz.adaptech`.
- **Kotlin**: `is` — ключевое слово, нельзя как имя переменной (было `val is = ...`); `combine()` максимум 5 Flow — больше вкладываем в пары; block-комментарии **вкладываются** — `photos/*` внутри KDoc открывал новый `/*`.
- **Compose**: `collectAsStateWithLifecycle` для холодных Flow требует `initialValue`; делегат `by remember` требует импортов `getValue/setValue`.
- **Room**: имя параметра DAO должно совпадать с `:bind` в запросе.
- **FileProvider**: каждый каталог, из которого раздаём файлы, обязан быть в `res/xml/file_paths.xml` (кэш камеры, cache/docs, files/photos) — иначе краш на `getUriForFile`.
- **Windows→CI**: `gradlew` коммитится без бита исполнения — после первого коммита из Windows: `git update-index --chmod=+x gradlew`.
- **CI**: пустая env-переменная `KEYSTORE_FILE=""` ломала signingConfig — проверять `isNullOrBlank`, не только null; созданию релиза нужен `permissions: contents: write` в workflow.
- **Манифест**: транзитивные сетевые разрешения WorkManager вырезаются `tools:node="remove"` (ACCESS_NETWORK_STATE, FOREGROUND_SERVICE).
- **Java regex + кириллица**: квантификатор сразу после кириллической буквы (`Даты?`) разбирается некорректно (проверено на JDK 22) — использовать символьные классы (`Дат[аы]`). `CASE_INSENSITIVE` без `UNICODE_CASE` регистр кириллицы НЕ сворачивает — указывать варианты явно (`[Рр]одив`, `РЕЦЕПТ|Рецепт`).
- **Фото с камеры**: `BitmapFactory` игнорирует EXIF orientation, а `Bitmap.compress` его стирает — поворот применять до сохранения (`androidx.exifinterface`), иначе портретные снимки «боковые» и OCR бессилен.
- **16КБ-страницы прошивки**: нативные `.so`, выровненные на 4КБ (PT_LOAD align 0x1000), не грузятся на Android 15+/16 с 16КБ-страницами — `UnsatisfiedLinkError: dlopen failed` уже при `System.loadLibrary`, т.е. `catch (Exception)` не ловит (это Error). Проверка устройства: `adb shell getconf PAGESIZE`; проверка библиотеки: `readelf`/парсинг phdr `p_align`. Для нативных зависимостей требовать выравнивание 0x4000.
- **Tesseract4Android**: с 4.8.0 координаты Maven сменились — новые версии только как `cz.adaptech.tesseract4android:tesseract4android` (JitPack), старые `com.github.adaptech-cz.Tesseract4Android:*` кончаются (4.9.0 там нет). Классы по-прежнему в `com.googlecode.tesseract.android`.
- **adb в Git Bash (Windows)**: MSYS конвертирует аргументы вида `/sdcard/...` в виндовые пути (`D:/Git/sdcard/...`) — `adb push` «успешно» кладёт файл непонятно куда. Всегда `export MSYS_NO_PATHCONV=1` перед командами с device-путями.
- **Эмуляторы для проверки нативного кода**: `sdkmanager "system-images;android-35;google_apis;x86_64"` (обычный 4КБ) и `"system-images;android-35;google_apis_ps16k;x86_64"` (16КБ); эмулятор не поднимается без `-no-window -gpu swiftshader_indirect` на безмониторных сессиях. Инструментальные тесты: `./gradlew :app:connectedDebugAndroidTest` (AVD должен быть запущен).

## 8. Как делать правки (порядок)

1. Правки в `E:\Danil\MedTracker`, `./gradlew :app:testDebugUnitTest` → зелёные.
2. Коммит в `main`, пуш, дождаться зелёного CI (GitHub Actions).
3. Для релиза: обновить `versionName` при смене минора/мажора, тег `vX.Y.Z`, пуш тега. APK появится в Releases через ~5 мин.
4. Никаких персональных данных в коде/тестах/доках; тестовые данные — только вымышленные из Приложения Б ТЗ.
