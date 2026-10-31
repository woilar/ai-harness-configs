# Learning Detour — OpenCode V2 Desktop

Отдельная полноценная Coach-сессия для обучения из рабочего диалога.
Рабочая история остаётся на месте; назад попадает краткий итог в **черновик**.
Плагин не отправляет его, не включает Executor и не копирует стенограмму.

**Статус: experimental MVP; Desktop smoke test НЕ ВЫПОЛНЕН.** Сборка и тесты
проверяют SDK-контракты, ядро, server adapter и draft delivery на mock host.
Реальное открытие вкладок/composer, провайдер и модель требуют проверки пользователем.
До этого полный Definition of Done не выполнен.

## Совместимость и исследование

Цель — **OpenCode 2.2.5**, tag `v2.2.5`, commit
`9bbb8ce18c80f099e0e448775f59d965ead40a21`, а не изменяемая ветка dev.
Версия Desktop пользователя неизвестна; setup на другой версии прекращается,
пока адаптер не перепроверен. GUI API официально experimental.

Исследованные первичные источники этого тега:
- [Session API](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/plugin/src/promise/session.ts) и [типы клиента](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/client/src/promise/generated/types.ts): create с id/agent/location/metadata/permissions; parentID необязателен.
- [GUI API](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/plugin/src/gui/host-apis.ts), [реализация](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/app/src/runtime/plugin/host-apis.tsx), [вкладки](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/app/src/shell/tabs/tabs.tsx), [mounted-session](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/app/src/runtime/plugin/mounted-session.ts): root composer работает; child composer ограничен; вставки могут ожидать загрузки draft, экран следует за навигацией.
- [GUI bundle](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/plugin/src/gui/vite.ts) и [Desktop loader](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/desktop/src/main/plugin/host.ts): export `./gui-experimental`, один CJS bundle, host предоставляет SDK/Solid. GUI обнаруживается у **локального sidecar**.
- [Plugin storage](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/core/src/plugin/host.ts): официальный серверный KV с namespace plugin ID. Это отдельная область от [GUI persistence](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/app/src/runtime/plugin/host-apis.tsx).
- [RPC](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/client/src/promise/rpc.ts): типизированные методы и события между сервером и GUI.

## Архитектура

`src/contracts.ts` — ограниченные checkpoint/result и RPC; `core.ts` — state machine,
резервирование ID, идемпотентность и retention; `index.ts` — серверный plugin,
официальные storage/session/tool/context hooks; `gui.ts` — вкладки и навигация;
`draft.ts` — безопасная доставка в composer без отправки. GUI хранит только
кратковременные флаги отображения; источник истины — server KV.
Методология обучения, условия выбора detour и Learning Backlog остаются в ролях.

## Установка (Windows/macOS/Linux)

Нужны Node.js 22+, npm, OpenCode Desktop 2.2.5 и настроенная модель.
Bash не требуется; следующие команды работают также в PowerShell:

```text
cd opencode/plugins/learning-detour
npm ci
npm run build
node scripts/install.mjs --create-coach --dry-run
node scripts/install.mjs --create-coach
node scripts/install.mjs --verify
```

Конфиг — `XDG_CONFIG_HOME/opencode`, иначе `~/.config/opencode`, как в 2.2.5.
Можно задать `--config "абсолютный путь/opencode.jsonc"`, в том числе Windows-путь.
Если CLI нет в PATH, вручную проверьте версию Desktop в About и передайте
`--version 2.2.5`. Это явное ручное подтверждение, не автоматическая проверка GUI.

Установщик добавляет в **plugins** (plural) file URI этой папки и options,
сохраняя JSONC comments/чужие поля. Перед записью проверяются конфликты, дубли
ключей, типы полей, build и версия; symlink-конфиг не заменяется. Если есть оба
JSON/JSONC — требуется --config. Изменение под lock с проверкой старого содержания,
уникальным backup и atomic rename; повторный запуск идемпотентен.
Stale lock не удаляется автоматически: сначала убедитесь, что установщик не работает.
--dry-run не пишет. --verify проверяет конфиг и bundles, но не реальную GUI-загрузку.

`--create-coach` создаёт минимального **primary agent**, только если его нет.
Существующие агенты не перезаписываются. Skill `coach` и selectable agent `coach`
различаются; для создания сессии нужен второй. При использовании framework сначала
установите его обычным установщиком; плагин работает и с чужими агентами без него.
После установки переоткройте Desktop. Существующие policies/permissions сохраняются;
плагин не обходит запросы разрешений. Для remote-сервера GUI-пакет нужен также
на локальном sidecar, а server plugin — на используемом сервере.

## Настройка ролей

```text
node scripts/install.mjs --coach-agent mentor --source-agents build,plan --create-coach
```

Options: coachAgent (coach), sourceAgents (build,plan,copilot,executor),
retentionDays (14, допустимо 1–90). Это agent IDs, не имена skills.
При использовании Copilot/Executor skills внутри build/plan их IDs допустимы.
Coach — enabled primary/all; его ID должен отличаться от источников.
Параметры применяются после перезагрузки плагина.

## Переход и обучение

Пользователь: «Не понимаю атомарность Redis, хочу глубоко разобраться».
Модель по правилам framework объясняет переход, сохраняет пробел и вызывает
learning_detour_start с коротким checkpoint: цель, следующий согласованный шаг,
вопрос, фаза, флаги и указатель договорённостей. Специальные команды не нужны;
дополнительного LLM-классификатора нет. Короткие вопросы остаются в текущем диалоге;
при неоднозначности модель предлагает переход до вызова инструмента.

Сервер записывает CREATING с зарезервированным Coach ID, затем создаёт **root
session без parentID/fork** и выбирает Coach. После ACTIVE GUI открывает отдельную
вкладку, не закрывая source. В ней настоящий composer и обычный выбор модели.
**Поддерживаемый fallback начала:** GUI заполняет вопрос в черновик Coach,
пользователь нажимает Send. Плагин не выдаёт программный контекст за пользовательскую
реплику и сам не отправляет первый запрос. Автоматический старт урока не реализован.

Focused Learning ограничивает начальную цель ближайшим рабочим вопросом,
но не запрещает расширять тему; нет лимита сообщений или таймера обучения.
Вложенные detours не создаются. Visual Explainer необязателен и не устанавливается.
Исходные рабочие инструменты блокируются до возврата/отмены. До перехода нужно
закончить/проверить уже начатый шаг; плагин не останавливает фоновые процессы,
которые были запущены ранее. Обычный Handoff без плагина сохраняется.

## Возврат и восстановление

«Пока хватит, вернёмся к задаче» → Coach вызывает learning_detour_return с итогом:
что выяснено, вопрос, неизвестное, sufficient/partial/unverified. Sufficient требует
самостоятельного подтверждения, не просто согласия. GUI «вернуться без проверки»
сохраняет честный unverified итог. Возврат не требует освоения всей темы.

Итог сохраняется как RETURNING даже при пропавшем source. GUI проверяет наличие
source и отсутствие генерации, открывает его и **вставляет итог в draft**, сохраняя
существующий текст и attachments. Ни prompt, synthetic, noReply, submit, ни переключение
агента на source не используются. После сетевых await повторно проверяется server/session.
Синхронная вставка подтверждается немедленно; queued вставка не дублируется при poll.
Отправка пользователем отмеченного draft тоже подтверждает доставку на сервере.
COMPLETED означает доставку, **не освоение темы и не разрешение менять код**.
Пользователь просматривает черновик и явно просит следующий рабочий шаг.

Закрытие вкладки не удаляет сессию. Ручной переход source → Coach не завершает detour:
он остаётся ACTIVE. После перезапуска открыть source и выбрать GUI-действие
«открыть связанную сессию». Старые ACTIVE не воруют фокус автоматически.
GUI использует RPC changed и poll раз в 3 секунды для переподключения; наличие GUI
подтверждает lease на 15 секунд. Просроченная связь не разрешает незаметно открыть
detour из CLI без Desktop.

Доступные GUI-действия в обычной command palette (slash-команды не нужны):
- открыть связанную сессию;
- вернуться без проверки;
- восстановить неудавшееся создание;
- отменить detour, сохранив обе сессии;
- показать сохранённый итог, если source удалён/недоступен или draft утрачен.

CREATING → ACTIVE → RETURNING → COMPLETED, также FAILED/CANCELLED.
При восстановлении FAILED используется тот же зарезервированный Coach ID и metadata;
crash между create и ACTIVE не создаёт второй Coach. Повторный start возвращает
существующий detour; разные source могут учиться независимо. Первый return выигрывает.
Если source busy/недоступен, результат остаётся и доставка повторится при доступности.

## Конфиденциальность и область данных

В checkpoint/result — только безопасные краткие обобщения. Исходники, diff,
конфигурации, секреты, закрытые бизнес-правила, production data и логи запрещены.
Схемы ограничивают размеры, отклоняют code fences/некоторые форматы токенов;
это **не универсальный NDA scanner**. Историю сессий плагин не читает/не копирует.
Directory, agent/session/server IDs нужны для связи и остаются на рабочем сервере.
Storage — официальный KV с namespace learning-detour. При remote-сервере он удалённый;
GUI не имеет собственной долговечной копии, но итог становится обычным Desktop draft.
Доступ к серверу означает потенциальный доступ к этим данным; storage не является
отдельной границей NDA. Использовать только разрешённый сервер/провайдер модели.

Coach получает session permissions ask для действий; чужие разрешения source не
переносятся. Это не sandbox: пользователь может осознанно разрешить действие.
Рабочую реализацию запрещают инструкции Coach; правила платформы остаются обязательны.
Learning Backlog ведут роли отдельно по существующей политике; в личное хранилище
плагин рабочий код не переносит. Карточки и их статусы не заменяются detour-result.
Terminal KV записи удаляются при setup по retentionDays; незавершённые сохраняются.
История OpenCode-сессий и drafts этим не удаляется, подчиняется политике среды.
Не создаются дополнительная БД или неограниченный журнал событий.

## Удаление и переносимость

```text
node scripts/install.mjs --remove
```

Удаляется только собственная plugins-запись и неизменённый минимальный Coach,
созданный установщиком. Сессии, KV, backups и Learning Backlog остаются.
Для переноса скопировать только эту папку и выполнить npm ci/build/install.
Нет импортов за пределы папки, абсолютных путей к framework и зависимости от shared/agent.
При перемещении удалить прежний file URI после проверки; другая установка вызывает
конфликт до записи. Связи другого сервера автоматически не мигрируют.

## Известные ограничения

- Реальный Desktop/LLM smoke test не выполнен. Не заявляется полная готовность интеграции.
- GUI API experimental; допускается только 2.2.5. Remote GUI discovery требует sidecar-пакета.
- Первый учебный draft отправляет пользователь; возврат также остаётся draft.
- Одновременное управление одним source из нескольких Desktop-окон не поддерживается:
  composer persistence не предоставляет межоконный atomic acknowledgement.
- Серверная сериализация действует в plugin instance location; перезапуск восстанавливает
  записи из KV, но одновременные независимые процессы с общим KV не поддерживаются.
- Пауза инструментов не отменяет уже работающий shell/subagent.
- Automatic detection — поведение основной модели, а не гарантированный UI-классификатор.

## Проверки

```text
npm ci
npm run build
npm test
```

Есть unit и host-adapter integration tests: схемы и state machine; mapping; restart,
recovery и duplicate events; отсутствующий source; retention; JSONC и repeat/remove;
SDK types, server tool/RPC/context; draft delivery без генерации, смена вкладки
во время await, queued composer и истёкшая GUI lease. Это mock host tests,
не end-to-end тест Desktop или модели.

Обязательный **ручной smoke test — НЕ ВЫПОЛНЕН**:
1. Установить в Desktop 2.2.5, проверить загрузку плагина и модель.
2. В Copilot естественной фразой сообщить о фундаментальном непонимании.
3. Убедиться в отдельной root Coach-вкладке и сохранности source.
4. Отправить вопрос из draft, задать несколько вопросов, проверить composer и модель.
5. При наличии проверить Visual Explainer; отсутствие не должно ломать диалог.
6. Вручную переключиться source → Coach; detour должен остаться ACTIVE.
7. Вернуться естественной фразой; проверить краткий draft и отсутствие генерации source.
8. Повторить возврат; проверить отсутствие дубля и сохранность собственного draft.
9. Начать новый detour, перезапустить Desktop, восстановить через связанную сессию.
10. Проверить permissions, потерянный source и неудачное создание/recovery.
