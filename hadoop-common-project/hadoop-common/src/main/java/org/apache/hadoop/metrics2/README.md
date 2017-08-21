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


    2.hadoop metrics系统整体架构解读
       hadoop metrics系统整体架构上采用的是生产者消费者模式源头对应的接口是MetricsSource类，消费metrics的接口是MetricsSink类。hadoop源码中实现了两种类型的sink,ganglia和file,也就是说我们可以把metrics传给ganglia和文件，当然也可以自己实现sink，往更多的目的地写.
       对于metrics system而言，并不是直接操作source和sink的，还有两个适配器类MetricsSourceAdapter和MetricsSinkAdapter,他们分别包装了source和sink,一对一的关系，但在adapter里还有filter的存在，用以过滤从source里get到的metrics和流向sink的metrics。metrics系统运行时会遍历所有的的sink，因此每个source里的metrics会发到多个sink中，不过还有metricsFilter对metrics进行过滤。
       再来说说metricsSystem是怎样从source里get到metrics的。metrics系统里有一个MetricsCollectorImpl类，顾名思义，该类就是用来收集metrics的，当调用source的getMetrics（）方法时，会先将收集到的metrics放入这个collector中，然后返回collector中的Iterable，返回后metricsSystem将Iterable放入metricsBuffer中。至此，metrics的收集工作完毕。
       最后该说说metrics系统sink的工作流程了。metrics系统拿到metricsBuffer后，调用sinkAdapter的putMetrics（）方法，这里并没有做filter的逻辑，只是将metricsBuffer放入一个sinkquene的队列里。MetricsSinkAdapter实现了SinkQueue.Consumer，消费metricsBuffer的逻辑在实现自Consumer接口的consume（）方法里。sinkAdapter里有一个守护线层，不断的从sinkQuene里取metricsBuffer,然后consume,filter的逻辑是在consume（)里做的，经过filter过滤出的metrics才会用来去调用fileSink的putmetrics()方法，至此，metrics就写入到文件里了。


    3.hadoop-metrics2 其实是 hadoop-common 工具包中的一个小模块，它设计了一个完整的metrics使用方案，工作中正好用到了，这里从代码层面分析下其设计思路，并不会贴大段大段的代码

      代码分析
      hadoop-metrics2 的整个流程都被封装到了 MetricsSystem 中，随着这个类的启动，配置初始化->metrics生成->metrics投递的整个链路就串通了。

      配置初始化
      hadoop-metrics2 自己定义了一种配置，名字开头必须为 hadoop-metrics2 ，可以自定义尾缀，其中主要完成了 MetricsSource 和 MetricsSink 的一些配置，其中对 MetricsSink 有非常重的依赖，由于 hadoop-metrics2 将 MetricsSink 实现了插件化，而这种插件化是通过 SubsetConfiguration 完成初始化，所以这部分感觉使用起来并不是很灵活。

      在 MetricsSystem 正式启动之前，会先通过 configure 的调用完成配置加载及初始化

      metrics生成
      所有被 hadoop-metrics2 管理的数据源必须实现 MetricsSource 接口：

      public interfaceMetricsSource{ voidgetMetrics(MetricsCollector var1,booleanvar2); }
      实现好接口之后，你需要将其 register 到 MetricsSystem 中，之后它会对 MetricsSource 进行简单的包装并管理起来

      ， MetricsSystem 启动之后会启动一个 Timer 定时器来周期性执行 Metircs 的流转，并且 MetricsSystem 中生成的 MetricsCollector 对象，会在各个 MetricsSource 之间传递。

      由于之前注册过了 MetricsSource ，你的数据源就会在这时候被调用处理。

      你可以在有数据变动的时候利用 MetricsCollector 来构造 MetricsRecordBuilder 并添加数据

      这里再说一下 MutableMetric 和 MetricsRegistry 。

      MetricsRegistry 用来管理 hadoop-metrics2 的几种基本类型的 Metircs ，如： Gauge ， Couter ， Stat 等，并且这些 Metircs 都继承自 MutableMetric 。

      作为抽象父类的 MutableMetric 的方法 snapshot 很特别，他返回的是上一次的快照，并通过 changed 来判定是否发生数据变化，这样只需要在数据变动的时候投递 Metrics ，抽象类代码如下：

      public abstract classMutableMetric{ private volatile boolean changed = true; publicabstractvoidsnapshot(MetricsRecordBuilder var1,booleanvar2); publicvoidsnapshot(MetricsRecordBuilder builder){ this.snapshot(builder, false); } protectedvoidsetChanged(){ this.changed = true; } protectedvoidclearChanged(){ this.changed = false; } publicbooleanchanged(){ return this.changed; } } metrics投递
      在前面完成 Metrics 生成之后， MetricsSystem 会调用 publishMetrics 方法来完成数据投递。

      这里 hadoop-metrics2 还做了点特别的工作。

      一开始在完成配置加载后， MetricsSystem 对 MetricsSink 进行了包装，生成了 MetricsSinkAdapter ，这里主要是为了增加对 Metrics 的吞吐量的管理。

      每一个 MetricsSinkAdapter 内部都有一个 SinkQueue 用于数据缓冲并添加了重试逻辑。

      由于之前添加的Sink都实现了 MetricsSink 接口：

      public interfaceMetricsSinkextendsMetricsPlugin{ voidputMetrics(MetricsRecord var1); voidflush(); }
      在具体实现的 putMetrics 中你可以完成数据的具体操作，写文件也好，写DB也好，消息队列也好

      总结
      hadoop-metrics2 总体的设计思路很简洁，但是由于和 hadoop-common 绑定太死，会很笨重，尤其在包冲突问题上表现明显。

      另外作为一个二方包，仅仅作为数据投递，感觉设计的还是略微繁琐切易用性不够。

      考虑到 hadoop-metrics2 更多是为 hadoop 社区服务，可能初衷就没考虑通用性，加上其逻辑并不复杂，完全可以参考思路自己实现一套轻量的 Metrics 二方包。

      这才是合理的造轮子。
===========================================
    1.hadoop metrics
        NodeManagerMetrics,DataNodeMetrics,NameNodeMetrics,FairSchedulerMetrics,ClusterMetrics,ContainerMetrics,JvmMetrics,RpcMetrics,...

    2.