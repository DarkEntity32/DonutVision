DONUTVISION 1.8.0 — CRYSTAL CLUSTER ESP (Minecraft Fabric 26.1.2)

Что добавлено:
- Modules → Crystal Cluster ESP: подсветка полностью выросших аметистовых кластеров (minecraft:amethyst_cluster) фиолетовым цветом, сквозь стены.
- Линии к кластерам включаются общим переключателем Block tracers.
- Работает отдельно от Block ESP и Spawner ESP. Использует дальность Block ESP range (10–∞).
- Вкладка Colors показывает цвет Crystal clusters.
- Настройка crystalEsp сохраняется в config/donutvision.properties.
- В сохранённой конфигурации по умолчанию выключено — включи в меню.

УСТАНОВКА ЧЕРЕЗ GITHUB:
1. Распакуй ZIP с пятью Java-файлами.
2. Открой в своём GitHub: DonutVision-26.1.2/src/client/java/dev/donutvision/
3. Добавь пять .java файлов через Add file → Upload files, замени существующие и нажми Commit changes.
4. Чтобы название версии тоже было 1.8.0: замени gradle.properties в DonutVision-26.1.2/ и fabric.mod.json в DonutVision-26.1.2/src/client/resources/ из полного архива.
5. Если в workflow (.github/workflows/build.yml) жёстко прописано 1.7.0 в имени .jar или проверке версии — исправь на 1.8.0.
6. Actions → Build DonutVision → Run workflow. Скачай готовый JAR из Artifacts после зелёной галочки.
7. Удали старый .jar из Prism Launcher Mods и установи новый (оставь Fabric API).

ВАЖНО: Клиент может обнаружить только блоки в чанках, присланных сервером.
Функция определяет стандартный minecraft:amethyst_cluster, но не кастомные блоки с собственным ID и не аметистовые почки меньших стадий.

ПРОВЕРКА: Java-код блокового сканера и GUI был скомпилирован через тестовые заглушки (Java 21), а проверки появления/удаления/сохранения целей прошли. Реальная компиляция Fabric 26.1.2/Java 25 и игровой тест НЕ проводились.
