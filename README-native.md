# ml-server в GraalVM Native Image

Зачем: ml-server — только прокси к LLM, поэтому его можно собрать в нативный бинарник. Вместо ~400 МБ на JVM —
~100 МБ, старт за десятки миллисекунд (документ 22). Сервер на движке **CIO** (Netty убран).

Сборке нужно **6–8 ГБ свободной памяти** — собираем на сервере, не на слабом ПК. На ПК подготовлено и проверено всё,
кроме самой нативной компиляции.

## Что подготовлено
| Что | Где |
|---|---|
| Плагин сборки и параметры | `build.gradle.kts`, блок `graalvmNative` |
| Метаданные рефлексии и ресурсов | `src/main/resources/META-INF/native-image/ru.tpu/ml-server/reachability-metadata.json` (собраны агентом, см. ниже) |
| Образ для сборки и запуска | `Dockerfile.native` (GraalVM 24.0.2 → debian slim, пользователь не root) |
| Корневой сертификат НУЦ Минцифры | `certs/russian_trusted_root_ca.pem` — встраивается в бинарник, без него HTTPS к GigaChat API не установится |
| Запуск под systemd без Docker | `etc/ml-server-native.service` |
| Нагрузка для проверки | `tools/sse_load.py` |

## Сборка на сервере
```bash
docker build -f Dockerfile.native -t mytpu/ml-server:native .
```
Сборка идёт 3–8 минут. Если упала с `Killed` / `exit 137`, памяти не хватило: закройте остальные контейнеры или добавьте swap.

## Проверка после сборки
```bash
# ключ — в файле с правами 0600 (не в командной строке и не в истории оболочки)
docker run -d --name ml-native --memory 128m --memory-swap 128m -p 127.0.0.1:8090:8080 \
  --env-file /etc/ml-server.env -e ML_PROVIDER=mock mytpu/ml-server:native
docker logs ml-native | head            # время старта
curl -s http://127.0.0.1:8090/health
export ML_API_KEY="$(sudo grep '^ML_API_KEY=' /etc/ml-server.env | cut -d= -f2)"
python3 tools/sse_load.py --url http://127.0.0.1:8090 --streams 20 & docker stats --no-stream ml-native
```
Ожидаем:
- старт ≤ 200 мс;
- `/v1/chat/completions` отвечает обычным ответом и потоком SSE, как JVM-версия;
- при 20 потоках RSS ≤ 128 МБ, без OOM;
- при `ML_MAX_CONCURRENT=1` лишние потоки получают 429, как раньше;
- с `ML_PROVIDER=openai` и настоящим `UPSTREAM_URL` по HTTPS ответ приходит (сертификаты встроены верно).

## Сертификат НУЦ Минцифры: закрепить отпечаток
Перед первой сборкой сверьте отпечаток `certs/russian_trusted_root_ca.pem` с опубликованным на gosuslugi.ru / gu-st.ru
(`sha256sum certs/russian_trusted_root_ca.pem`) и передайте его при сборке:
`docker build -f Dockerfile.native --build-arg RUSSIAN_CA_SHA256=<отпечаток> …` — подменённый файл тогда сорвёт сборку.

## Если код изменился: обновить метаданные
Native Image не видит рефлексию (kotlinx.serialization, logback) без подсказок. Их собирает агент трассировки
на обычной JVM — нативная компиляция для этого не нужна:
```bash
./gradlew installDist
PORT=8090 ML_API_KEY=devkey ML_PROVIDER=mock \
  $GRAALVM_HOME/bin/java "-agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/ru.tpu/ml-server" \
  -cp "build/install/ml-server/lib/*" ru.tpu.ml.ApplicationKt
# в другом терминале прогнать все пути: health, models, обычный ответ, поток, ошибки 400/401/429,
# и то же с ML_PROVIDER=openai (UPSTREAM_URL можно направить на второй ml-server в режиме mock)
```
GraalVM агента и образа сборки должна быть одной версии (сейчас 24): формат файла метаданных зависит от неё.

## Под systemd
```bash
docker create --name ml-native mytpu/ml-server:native
docker cp ml-native:/app/ml-server /opt/ml-server/ml-server && docker rm ml-native
sudo cp etc/ml-server-native.service /etc/systemd/system/ml-server.service
sudo systemctl daemon-reload && sudo systemctl restart ml-server
```
Переменные — те же, что у JVM-версии (`/etc/ml-server.env`). Флаги JVM (`-Xmx256m`, `MALLOC_ARENA_MAX`) нативной версии не нужны.
