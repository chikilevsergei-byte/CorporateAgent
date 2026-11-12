# Сборка исходников 5.4.5 Portable

Для обычного использования скачайте готовый архив из Releases. Этот каталог содержит исходники, соответствующие JAR Portable-релиза; пользовательские данные и Java Runtime сюда не входят.

## Windows: сборка приложения

Требуются **JDK 21**, Windows PowerShell и Python 3 для загрузки библиотек. Это требования для разработчика, а не для пользователя готового релиза.

Из этого каталога:

```powershell
python .\download_libs.py
.\build.ps1 -JdkHome 'C:\путь\к\jdk-21' -Test
```

Скрипт загрузки сверяет SHA-256 каждой библиотеки с `dependency-lock.json`. В существующем lock-файле поле `scope` унаследовано из предыдущей разработки; для полной сборки и функций обработки документов загружаются **все** перечисленные зависимости.

`build.ps1 -Test` компилирует код, запускает штатные Java-тесты и создаёт производственный JAR в `dist`. Имя выбранного JAR записывается в `dist/current-jar.txt`.

Для ручного запуска из исходников, после сборки:

```powershell
$jar = (Get-Content .\dist\current-jar.txt -Raw).Trim()
& 'C:\путь\к\jdk-21\bin\java.exe' '-Dfile.encoding=UTF-8' '-Dcorpagent.port=0' "-Dcorpagent.install=$PWD" "-Dcorpagent.base=$PWD\data" -cp "$jar;lib\*" corpagent.App
```

В журнале появится локальная ссылка. Она содержит токен доступа к этому экземпляру: не публикуйте её.

## Источник Portable-запускателя

`packaging/PortableLauncher.java` отвечает за проверку готовности экземпляра, запуск Mac-версии и завершение через локальный API. `Start.command` — шаблон Mac-запускателя; `@ARCH@` заменяется на `arm64` или `x86_64` при упаковке. `Stop.command` получается из этого шаблона с параметром `--stop`.

Скомпилировать вспомогательный запускатель можно JDK 21:

```powershell
$jar = (Get-Content .\dist\current-jar.txt -Raw).Trim()
New-Item -ItemType Directory -Force .\build_test\launcher | Out-Null
& 'C:\путь\к\jdk-21\bin\javac.exe' --release 21 -encoding UTF-8 -cp $jar -d .\build_test\launcher .\packaging\PortableLauncher.java
& 'C:\путь\к\jdk-21\bin\jar.exe' --create --file .\launcher.jar -C .\build_test\launcher .
```

Для самостоятельной упаковки нужны Java Runtime 21 для каждой целевой ОС/архитектуры и все библиотеки из lock-файла. JRE и сторонние зависимости распространяются со своими лицензиями; включайте соответствующие лицензионные файлы. Готовые архивы релиза уже содержат необходимые файлы.

## Проверка

Результаты опубликованной версии описаны в [заметках релиза](../../docs/RELEASE-5.4.5-portable.md). `packaging/PortablePathsProbe.java` проверяет преобразование внутренних путей Windows/macOS. `packaging/verify_windows.py` — приёмочная проверка готового Windows-архива; в локальном процессе упаковки она запускалась рядом с каталогом `releases`.

Сборка/запуск на macOS в этой среде не выполнялись. Готовые Mac-архивы проверены статически; инструкция не утверждает наличие проверки на реальном Mac.
