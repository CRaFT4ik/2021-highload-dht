# 2021-highload-dht

Курсовой проект в рамках обучающей программы "[Технополис](https://polis.mail.ru)" по дисциплине [Высоконагруженные вычисления](https://polis.mail.ru/curriculum/program/discipline/1257/).

## Этап 4. Шардирование

Для тестирования с помощью wrk2 были подобраны оптимальные параметры для тестируемого
железа (процессор: AMD 5800H 8 ядер / 16 потоков, RAM: 16Гб, SSD NVMe).
Скрипт запуска можно увидеть тут: [wrk2.sh](../../profiling/wrk2.sh).
Все тесты проводились на одних и тех же параметрах.

wrk2 запускался одновременно для PUT и GET запросов. Запуск GET был отложенным
на 10 секунд, чтобы данные уже были записаны PUT.

Для кластерной реализации wrk2 работал только по одному узлу, используя набор ключей
всех нод.

### [Сервер на одном узле](https://github.com/CRaFT4ik/2021-highload-dht/blob/stage_3/)

Возьмем сервер, имеющийся на конец третьего этапа, и произведем его профилирование.

- [wrk2 PUT](profiling/one_node/wrk2_workers32_put.txt)
- [wrk2 GET](profiling/one_node/wrk2_workers32_get.txt)
- [async-profiler CPU](profiling/one_node/profiler_cpu_workers32.html)
- [async-profiler ALLOC](profiling/one_node/profiler_alloc_workers32.html)
- [async-profiler LOCK](profiling/one_node/profiler_lock_workers32.html)

Видим довольно низкие задержки.
Однако мы хотим сделать горизонтальное масштабирование системы, и далее введем ещё 2 узла. 

### [Сервер на трех узлах](https://github.com/CRaFT4ik/2021-highload-dht/blob/stage_4/)

В качестве алгоритма хеширования выбран Consistent Hashing.
В качестве кольца (ring) для этого алгоритма выбрано красно-черное дерево `TreeMap`.
Операция, которую часто предстоит делать с этим кольцом - поиск следующего элемента
за указанным. Для этих целей используется функция `ceilingKey` интерфейса `NavigableMap`.

Consistent Hashing реализован с виртуальными узлами, чтобы решить проблему неравномерного
распределения данных между физическими узлами. Физические узлы мапятся на виртуальные,
виртуальные мапятся на кольцо. Каждому физическому узлу назначено 30 виртуальных.

#### Реализация на алгоритме хеширования MD5

В качестве эксперимента умышленно был выбран алгоритм хеширования MD5.
Я предполагал, что вычисление хеша с таким алгоритмом может стать узким местом,
и забегая вперед, так и получилось. Но замеры проведены:

 - [wrk2 PUT](profiling/three_nodes/md5/wrk2_sharding_md5_put.txt)
 - [wrk2 GET](profiling/three_nodes/md5/wrk2_sharding_md5_get.txt)
 - [async-profiler CPU](profiling/three_nodes/md5/profiler_cpu_sharding_md5.html)
 - [async-profiler ALLOC](profiling/three_nodes/md5/profiler_alloc_sharding_md5.html)
 - [async-profiler LOCK](profiling/three_nodes/md5/profiler_lock_sharding_md5.html)

Note: Результаты измерений **реализации без кластера** приведены выше.

Видим, что совсем плохо. Из LOCK-профайла понимаем, что виновата хеш-функция.
Каждый запрос она вычисляется, и даже маленькое промедление может стать большой проблемой. 
Идем дальше.

#### Выбор функции хеширования

Встал вопрос: если не MD5, то кто? За ответом я обратился в Интернет.

В процессе поиска [обнаружил](http://cyan4973.github.io/xxHash/) benchmarks для различных
функций хеширования. К слову, MD5 стоит на последнем месте...

Нам не важны криптографические параметры хеш-функции, поэтому берем самую быструю: XXH3.
На той же странице предложены реализации для разных ЯП. Выбрана [эта](https://github.com/OpenHFT/Zero-Allocation-Hashing).

Также про эту функцию [написана статья](https://habr.com/ru/company/globalsign/blog/444144/), 
где помимо её описания, идет сравнивают с подобными хеш-функциями.

#### Реализация на алгоритме хеширования XXH3

После замены функции хеширования, она перестала быть узким местом:

- [wrk2 PUT](profiling/three_nodes/xxh3/wrk2_sharding_xxh3_put.txt)
- [wrk2 GET](profiling/three_nodes/xxh3/wrk2_sharding_xxh3_get.txt)
- [async-profiler CPU](profiling/three_nodes/xxh3/profiler_cpu_sharding_xxh3.html)
- [async-profiler ALLOC](profiling/three_nodes/xxh3/profiler_alloc_sharding_xxh3.html)
- [async-profiler LOCK](profiling/three_nodes/xxh3/profiler_lock_sharding_xxh3.html)

Результаты улучшились по сравнению с тем, что было на медленной хеш-функции.
Однако они не идут в сравнение с теми измерениями, когда ещё не было кластера.
Особенно это заметно на PUT-запросах, и менее выражено на GET.

В LOCK-профайле мы видим новую колонку: она связана с застреванием потоков
на перенаправлении запросов.

В реализации на данный момент отчета сделано так:
 1. Пришел какой-либо запрос, он находится в потоке селектора;
 2. Если это запрос на статус сервера, отвечаем сразу;
 3. Если это запрос, который требует перенаправления, перенаправляем в этом же потоке;
 4. Если это запрос, которые не требует перенаправления, отправляем его в пул рабочих 
потоков.

Также изменилось вот что: из отчета wrk2 не видно, чтобы сервер теперь возвращал
множественные не 2xx и 3xx ответы, когда он не мог обработать запрос. Поскольку данные
теперь распределены, снизилась нагрузка на рабочие потоки.

Работа сервера в режиме прокси, как видно, существенная. И замедление селекторов
таким образом явно не делает ситуацию лучше.

А что если правильнее обрабатывать перенаправления в рабочем пуле потоков?
Ожидаемый результат: значительно увеличится количество не 2xx и 3xx ответов.
Попробуем...

#### Реализация с выполнением прокси-работы в рабочем пуле потоков

Результаты измерений:

- [wrk2 PUT](profiling/three_nodes/proxy_by_workes/wrk2_sharding_proxy_by_workers_put.txt)
- [wrk2 GET](profiling/three_nodes/proxy_by_workes/wrk2_sharding_proxy_by_workers_get.txt)
- [async-profiler CPU](profiling/three_nodes/proxy_by_workes/profiler_cpu_sharding_proxy_by_workers.html)
- [async-profiler ALLOC](profiling/three_nodes/proxy_by_workes/profiler_alloc_sharding_proxy_by_workers.html)
- [async-profiler LOCK](profiling/three_nodes/proxy_by_workes/profiler_lock_sharding_proxy_by_workers.html)

Исходя из этого заметим:
- В CPU-профайле: теперь много времени рабочий пул потоков тратит на redirect.
Это время превышает время для операций над полезной нагрузкой (range и upsert); 
- В ALLOC-профайле: много аллокаций под redirect (HttpClient);
- В LOCK-профайле: потоки блокируются на HttpClient. Количество блокировок 
превышает то же число блокировок в LOCK-профайле, когда redirect выполняли потоки-селекторы.

Стало ли лучше? Сомнительно. Да, тайминги стали сравнимы с реализацией без кластера,
однако очень много запросов отбрасывается, и блокировок прибавилось.

На ум приходит идея ввести отдельный пул потоков для обработки прокси-запросов.

#### Выделение отдельного пула потоков для обработки прокси-запросов

Число соединений для каждого HTTP-клиента каждого узла кластера было установлено
в (n-1) раз больше, чем число потоков, работающих в пуле по обработке прокси-запросов,
где n - количество узлов в кластере.

Прокси-запросы обрабатываются в пуле потоков с ограниченной очередью. Лимит очереди
в 2 раза больше числа потоков. В точности также, как сделано для рабочего пула потоков. 

Результаты измерений:

- [wrk2 PUT](profiling/three_nodes/proxy_by_new_threadpool/wrk2_sharding_proxy_separated_put.txt)
- [wrk2 GET](profiling/three_nodes/proxy_by_new_threadpool/wrk2_sharding_proxy_separated_get.txt)
- [async-profiler CPU](profiling/three_nodes/proxy_by_new_threadpool/profiler_cpu_sharding_proxy_separated.html)
- [async-profiler ALLOC](profiling/three_nodes/proxy_by_new_threadpool/profiler_alloc_sharding_proxy_separated.html)
- [async-profiler LOCK](profiling/three_nodes/proxy_by_new_threadpool/profiler_lock_sharding_proxy_separated.html)

LOCK-профайл стал немного лучше, чем если бы обработка прокси-запросов выполнялась в
рабочем пуле потоков. Также и число не 2xx-3xx ответов уменьшилось в том же сравнении.

Однако в измерениях wrk2 видим большой скачок во времени ответа между `99.0`-`99.9`%.
Профайлы CPU и ALLOC сравнимы с предыдущей реализацией, как и профайл LOCK. 
Изменение размера очереди пула прокси-потоков как в большую, так и в меньшую сторону, 
не приводили к исправлению или уменьшению данной проблемы.

Затрудняюсь ответить, чем вызван такой резкий скачок. Возможно, это связано со сборкой мусора JVM.