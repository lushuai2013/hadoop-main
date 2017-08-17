/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.tools;

import java.io.IOException;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Cluster;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobSubmissionFiles;
import org.apache.hadoop.tools.CopyListing.*;
import org.apache.hadoop.tools.mapred.CopyMapper;
import org.apache.hadoop.tools.mapred.CopyOutputFormat;
import org.apache.hadoop.tools.util.DistCpUtils;
import org.apache.hadoop.util.ShutdownHookManager;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import com.google.common.annotations.VisibleForTesting;

/**
 * DistCp is the main driver-class for DistCpV2.
 * For command-line use, DistCp::main() orchestrates the parsing of command-line
 * parameters and the launch of the DistCp job.
 * For programmatic use, a DistCp object can be constructed by specifying
 * options (in a DistCpOptions object), and DistCp::execute() may be used to
 * launch the copy-job. DistCp may alternatively be sub-classed to fine-tune
 * behaviour.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class DistCp extends Configured implements Tool {

  /**
   * Priority of the shutdown hook.
   */
  static final int SHUTDOWN_HOOK_PRIORITY = 30;

  static final Log LOG = LogFactory.getLog(DistCp.class);

  private DistCpOptions inputOptions;
  private Path metaFolder;

  private static final String PREFIX = "_distcp";
  private static final String WIP_PREFIX = "._WIP_";
  private static final String DISTCP_DEFAULT_XML = "distcp-default.xml";
  static final Random rand = new Random();

  private boolean submitted;
  private FileSystem jobFS;

  /**
   * Public Constructor. Creates DistCp object with specified input-parameters.
   * (E.g. source-paths, target-location, etc.)
   * @param inputOptions Options (indicating source-paths, target-location.)
   * @param configuration The Hadoop configuration against which the Copy-mapper must run.
   * @throws Exception
   */
  public DistCp(Configuration configuration, DistCpOptions inputOptions) throws Exception {
    Configuration config = new Configuration(configuration);
    config.addResource(DISTCP_DEFAULT_XML);
    setConf(config);
    this.inputOptions = inputOptions;
    this.metaFolder   = createMetaFolderPath();
  }

  /**
   * To be used with the ToolRunner. Not for public consumption.
   */
  @VisibleForTesting
  DistCp() {}

  /**
   * Implementation of Tool::run(). Orchestrates the copy of source file(s)
   * to target location, by:
   *  1. Creating a list of files to be copied to target.
   *  2. Launching a Map-only job to copy the files. (Delegates to execute().)
   * @param argv List of arguments passed to DistCp, from the ToolRunner.
   * @return On success, it returns 0. Else, -1.
   */
  @Override
  public int run(String[] argv) {
    if (argv.length < 1) {
      OptionsParser.usage();
      return DistCpConstants.INVALID_ARGUMENT;
    }
    
    try {
      //解析参数
      inputOptions = (OptionsParser.parse(argv));
      //设置inputOptions和job config目标路径是否存在
      setTargetPathExists();
      LOG.info("Input Options: " + inputOptions);
    } catch (Throwable e) {
      LOG.error("Invalid arguments: ", e);
      System.err.println("Invalid arguments: " + e.getMessage());
      OptionsParser.usage();      
      return DistCpConstants.INVALID_ARGUMENT;
    }
    
    try {
      execute();
    } catch (InvalidInputException e) {
      LOG.error("Invalid input: ", e);
      return DistCpConstants.INVALID_ARGUMENT;
    } catch (DuplicateFileException e) {
      LOG.error("Duplicate files in input path: ", e);
      return DistCpConstants.DUPLICATE_INPUT;
    } catch (AclsNotSupportedException e) {
      LOG.error("ACLs not supported on at least one file system: ", e);
      return DistCpConstants.ACLS_NOT_SUPPORTED;
    } catch (XAttrsNotSupportedException e) {
      LOG.error("XAttrs not supported on at least one file system: ", e);
      return DistCpConstants.XATTRS_NOT_SUPPORTED;
    } catch (Exception e) {
      LOG.error("Exception encountered ", e);
      return DistCpConstants.UNKNOWN_ERROR;
    }
    return DistCpConstants.SUCCESS;
  }

  /**
   * Implements the core-execution. Creates the file-list for copy,
   * and launches the Hadoop-job, to do the copy.
   * @return Job handle
   * @throws Exception
   */
  public Job execute() throws Exception {
    Job job = createAndSubmitJob();

    if (inputOptions.shouldBlock()) {
      waitForJobCompletion(job);
    }
    return job;
  }

  /**
   * 创建MR任务，准备数据，设定数据输入格式，并把任务提交到hadoop集群运行，最后等待任务执行完毕
   * Create and submit the mapreduce job.
   * @return The mapreduce job object that has been submitted
   */
  public Job createAndSubmitJob() throws Exception {
    assert inputOptions != null;
    assert getConf() != null;
    Job job = null;
    try {
      synchronized(this) {
        //Don't cleanup while we are setting up.
        /**
         * metaFolder
         * metafolder是DISTCP工具准备元数据的地方，在createMetaFolderPath()中会结合一个随机数生成一个工作目录，
         * 在这个目录中迟点会通过getFileListingPath()生成fileList.seq文件，然后往这个文件中写入数据，
         * 这是一个SequenceFile文件，即Key/Value结构的序列化文件，这个文件里将存放所有需要拷贝的源目录/文件信息列表。
         * 其中Key是源文件的Text格式的相对路径，即relPath；而Value则记录源文件的FileStatus格式的org.apache.hadoop.fs.FileStatus信息，
         * 这里FileStatus是hadoop已经封装好了的描述HDFS文件信息的类，但是DISTCP为了更好的处理数据，
         * 重新继承并封装了CopyListingFileStatus类
         *
         * metafolder目录中的fileList.seq最终会作为参数传递给MR任务中的Mapper。
         */
        metaFolder = createMetaFolderPath();
        jobFS = metaFolder.getFileSystem(getConf());
        job = createJob();
      }
      if (inputOptions.shouldUseDiff()) {
        if (!DistCpSync.sync(inputOptions, getConf())) {
          inputOptions.disableUsingDiff();
        }
      }
      //元数据生成 创建fileList.seq文件
      createInputFileListing(job);

      job.submit();
      submitted = true;
    } finally {
      if (!submitted) {
        cleanup();
      }
    }

    String jobID = job.getJobID().toString();
    job.getConfiguration().set(DistCpConstants.CONF_LABEL_DISTCP_JOB_ID, jobID);
    LOG.info("DistCp job-id: " + jobID);

    return job;
  }

  /**
   * Wait for the given job to complete.
   * @param job the given mapreduce job that has already been submitted
   */
  public void waitForJobCompletion(Job job) throws Exception {
    assert job != null;
    if (!job.waitForCompletion(true)) {
      throw new IOException("DistCp failure: Job " + job.getJobID()
          + " has failed: " + job.getStatus().getFailureInfo());
    }
  }

  /**
   * 设置inputOptions和job config目标路径是否存在
   * Set targetPathExists in both inputOptions and job config,
   * for the benefit of CopyCommitter
   */
  private void setTargetPathExists() throws IOException {
    Path target = inputOptions.getTargetPath();
    FileSystem targetFS = target.getFileSystem(getConf());
    boolean targetExists = targetFS.exists(target);
    inputOptions.setTargetPathExists(targetExists);
    getConf().setBoolean(DistCpConstants.CONF_LABEL_TARGET_PATH_EXISTS, 
        targetExists);
  }
  /**
   * Create Job object for submitting it, with all the configuration
   *
   * @return Reference to job object.
   * @throws IOException - Exception if any
   */
  private Job createJob() throws IOException {
    //distcp任务名称
    String jobName = "distcp";
    String userChosenName = getConf().get(JobContext.JOB_NAME);
    if (userChosenName != null)
      jobName += ": " + userChosenName;
    Job job = Job.getInstance(getConf());
    job.setJobName(jobName);
    //这条语句保证了输入文件会按照我们预设的格式被读取,Mapper数据输入格式由UniformSizeInputFormat.class这个类定义的
    job.setInputFormatClass(DistCpUtils.getStrategy(getConf(), inputOptions));
    //CopyMapper.class中则定义了每个map的工作逻辑，也就是拷贝的核心逻辑，任务提交到hadoop集群中运行时每个map就是根据这个逻辑进行工作的
    job.setJarByClass(CopyMapper.class);
    configureOutputFormat(job);

    job.setMapperClass(CopyMapper.class);
    job.setNumReduceTasks(0);
    job.setMapOutputKeyClass(Text.class);
    job.setMapOutputValueClass(Text.class);
    job.setOutputFormatClass(CopyOutputFormat.class);
    job.getConfiguration().set(JobContext.MAP_SPECULATIVE, "false");
    job.getConfiguration().set(JobContext.NUM_MAPS,
                  String.valueOf(inputOptions.getMaxMaps()));

    if (inputOptions.getSslConfigurationFile() != null) {
      setupSSLConfig(job);
    }

    inputOptions.appendToConf(job.getConfiguration());
    return job;
  }

  /**
   * Setup ssl configuration on the job configuration to enable hsftp access
   * from map job. Also copy the ssl configuration file to Distributed cache
   *
   * @param job - Reference to job's handle
   * @throws java.io.IOException - Exception if unable to locate ssl config file
   */
  private void setupSSLConfig(Job job) throws IOException  {
    Configuration configuration = job.getConfiguration();
    Path sslConfigPath = new Path(configuration.
        getResource(inputOptions.getSslConfigurationFile()).toString());

    addSSLFilesToDistCache(job, sslConfigPath);
    configuration.set(DistCpConstants.CONF_LABEL_SSL_CONF, sslConfigPath.getName());
    configuration.set(DistCpConstants.CONF_LABEL_SSL_KEYSTORE, sslConfigPath.getName());
  }

  /**
   * Add SSL files to distributed cache. Trust store, key store and ssl config xml
   *
   * @param job - Job handle
   * @param sslConfigPath - ssl Configuration file specified through options
   * @throws IOException - If any
   */
  private void addSSLFilesToDistCache(Job job,
                                      Path sslConfigPath) throws IOException {
    Configuration configuration = job.getConfiguration();
    FileSystem localFS = FileSystem.getLocal(configuration);

    Configuration sslConf = new Configuration(false);
    sslConf.addResource(sslConfigPath);

    Path localStorePath = getLocalStorePath(sslConf,
                            DistCpConstants.CONF_LABEL_SSL_TRUST_STORE_LOCATION);
    job.addCacheFile(localStorePath.makeQualified(localFS.getUri(),
                                      localFS.getWorkingDirectory()).toUri());
    configuration.set(DistCpConstants.CONF_LABEL_SSL_TRUST_STORE_LOCATION,
                      localStorePath.getName());

    localStorePath = getLocalStorePath(sslConf,
                             DistCpConstants.CONF_LABEL_SSL_KEY_STORE_LOCATION);
    job.addCacheFile(localStorePath.makeQualified(localFS.getUri(),
                                      localFS.getWorkingDirectory()).toUri());
    configuration.set(DistCpConstants.CONF_LABEL_SSL_KEY_STORE_LOCATION,
                                      localStorePath.getName());

    job.addCacheFile(sslConfigPath.makeQualified(localFS.getUri(),
                                      localFS.getWorkingDirectory()).toUri());

  }

  /**
   * Get Local Trust store/key store path
   *
   * @param sslConf - Config from SSL Client xml
   * @param storeKey - Key for either trust store or key store
   * @return - Path where the store is present
   * @throws IOException -If any
   */
  private Path getLocalStorePath(Configuration sslConf, String storeKey) throws IOException {
    if (sslConf.get(storeKey) != null) {
      return new Path(sslConf.get(storeKey));
    } else {
      throw new IOException("Store for " + storeKey + " is not set in " +
          inputOptions.getSslConfigurationFile());
    }
  }

  /**
   * Setup output format appropriately
   *
   * @param job - Job handle
   * @throws IOException - Exception if any
   */
  private void configureOutputFormat(Job job) throws IOException {
    final Configuration configuration = job.getConfiguration();
    Path targetPath = inputOptions.getTargetPath();
    FileSystem targetFS = targetPath.getFileSystem(configuration);
    targetPath = targetPath.makeQualified(targetFS.getUri(),
                                          targetFS.getWorkingDirectory());
    if (inputOptions.shouldPreserve(DistCpOptions.FileAttribute.ACL)) {
      DistCpUtils.checkFileSystemAclSupport(targetFS);
    }
    if (inputOptions.shouldPreserve(DistCpOptions.FileAttribute.XATTR)) {
      DistCpUtils.checkFileSystemXAttrSupport(targetFS);
    }
    if (inputOptions.shouldAtomicCommit()) {
      Path workDir = inputOptions.getAtomicWorkPath();
      if (workDir == null) {
        workDir = targetPath.getParent();
      }
      workDir = new Path(workDir, WIP_PREFIX + targetPath.getName()
                                + rand.nextInt());
      FileSystem workFS = workDir.getFileSystem(configuration);
      if (!DistCpUtils.compareFs(targetFS, workFS)) {
        throw new IllegalArgumentException("Work path " + workDir +
            " and target path " + targetPath + " are in different file system");
      }
      CopyOutputFormat.setWorkingDirectory(job, workDir);
    } else {
      CopyOutputFormat.setWorkingDirectory(job, targetPath);
    }
    CopyOutputFormat.setCommitDirectory(job, targetPath);

    Path logPath = inputOptions.getLogPath();
    if (logPath == null) {
      logPath = new Path(metaFolder, "_logs");
    } else {
      LOG.info("DistCp job log path: " + logPath);
    }
    CopyOutputFormat.setOutputPath(job, logPath);
  }

  /**
   *
   * 前面提到在metafolder目录中会生成fileList.seq文件，而这个文件是怎么生成以及文件里面保存些什么内容呢？
   * 这个逻辑就在createInputFileListing（job）中完成的
   *
   * Create input listing by invoking an appropriate copy listing
   * implementation. Also add delegation tokens for each path
   * to job's credential store
   *
   * @param job - Handle to job
   * @return Returns the path where the copy listing is created
   * @throws IOException - If any
   */
  protected Path createInputFileListing(Job job) throws IOException {
    //1. 创建一个空的seq文件
    Path fileListingPath = getFileListingPath();
    //构造buildListing实现类
    CopyListing copyListing = CopyListing.getCopyListing(job.getConfiguration(),
        job.getCredentials(), inputOptions);
    //3.buildListing（）方法往这个seq文件写入数据
    copyListing.buildListing(fileListingPath, inputOptions);
    return fileListingPath;
  }

  /**
   * Get default name of the copy listing file. Use the meta folder
   * to create the copy listing file
   *
   * @return - Path where the copy listing file has to be saved
   * @throws IOException - Exception if any
   */
  protected Path getFileListingPath() throws IOException {
    String fileListPathStr = metaFolder + "/fileList.seq";
    Path path = new Path(fileListPathStr);
    return new Path(path.toUri().normalize().toString());
  }

  /**
   * Create a default working folder for the job, under the
   * job staging directory
   *
   * @return Returns the working folder information
   * @throws Exception - EXception if any
   */
  private Path createMetaFolderPath() throws Exception {
    Configuration configuration = getConf();
    //stagingDir /tmp/hadoop-yarn/staging/data_platform/.staging
    Path stagingDir = JobSubmissionFiles.getStagingDir(
            new Cluster(configuration), configuration);
    // ${stagingDir}/_distcp{rand.nextInt()}   _distcp-1820135602
    Path metaFolderPath = new Path(stagingDir, PREFIX + String.valueOf(rand.nextInt()));
    if (LOG.isDebugEnabled())
      LOG.debug("Meta folder location: " + metaFolderPath);
    configuration.set(DistCpConstants.CONF_LABEL_META_FOLDER, metaFolderPath.toString());    
    return metaFolderPath;
  }

  /**
   * Main function of the DistCp program. Parses the input arguments (via OptionsParser),
   * and invokes the DistCp::run() method, via the ToolRunner.
   * @param argv Command-line arguments sent to DistCp.
   */
  public static void main(String argv[]) {
    int exitCode;
    try {
      DistCp distCp = new DistCp();
      Cleanup CLEANUP = new Cleanup(distCp);

      ShutdownHookManager.get().addShutdownHook(CLEANUP,
        SHUTDOWN_HOOK_PRIORITY);
      exitCode = ToolRunner.run(getDefaultConf(), distCp, argv);
    }
    catch (Exception e) {
      LOG.error("Couldn't complete DistCp operation: ", e);
      exitCode = DistCpConstants.UNKNOWN_ERROR;
    }
    System.exit(exitCode);
  }

  /**
   * Loads properties from distcp-default.xml into configuration
   * object
   * @return Configuration which includes properties from distcp-default.xml
   */
  private static Configuration getDefaultConf() {
    Configuration config = new Configuration();
    config.addResource(DISTCP_DEFAULT_XML);
    return config;
  }

  private synchronized void cleanup() {
    try {
      if (metaFolder == null) return;

      jobFS.delete(metaFolder, true);
      metaFolder = null;
    } catch (IOException e) {
      LOG.error("Unable to cleanup meta folder: " + metaFolder, e);
    }
  }

  private boolean isSubmitted() {
    return submitted;
  }

  private static class Cleanup implements Runnable {
    private final DistCp distCp;

    Cleanup(DistCp distCp) {
      this.distCp = distCp;
    }

    @Override
    public void run() {
      if (distCp.isSubmitted()) return;

      distCp.cleanup();
    }
  }
}
