参考：Hadoop监控代码分析
    　https://ymgd.github.io/codereader/2016/11/11/Hadoop%E7%9B%91%E6%8E%A7%E4%BB%A3%E7%A0%81%E5%88%86%E6%9E%90/


    Hadoop监控机制围绕Metrics System、Metrics Source、Metrics Sink三个主要的角色展开。
    顾名思义，Metrics System负责协调监控体系各实体的运作，Metrics Source负责在被监控进程处收集（统计）指标，Metrics Sink负责具体的将指标输送到希望的目的地（文件或者监控系统等）。

    代码实现中，MetricsSystem、MetricsSource、MeticsSink都是interface或者abstract class，其中MetricsSystem的默认实现类为MetricsSystemImpl，MetricsSource以及MeticsSink根据不同的需求场景，扩展出不同的实现类。

    指标监控的整个操作流程，可以归纳为如下几步：
        1.MetricsSource、MetricsSink通过调用MetricsSystem的register方法，注册到MetricsSystem上。
        2.MetricsSource在被监控进程中，将关键的指标存入到MetricsSource对象的内存中。
        3.MetricsSystem上启动定时器，每隔一段时间，调用已经在其上注册过的MetricsSource的getMetrics方法（MetricsSource在它的getMetrics逻辑中实现将内存中的指标数据反馈给MetricsSystem的目的（通过修改函数参数中传入的对象））。之后，MetricsSystem调用已经在其上注册过的MetricsSink的putMetrics方法，MetricsSink在各自的putMetrics方法中，实现将指标实际传输到目的地的逻辑。


    1. 扩展resourcemanager的metrics  参考：https://github.com/linyiqun/hadoop-yarn/tree/master/RMMetric