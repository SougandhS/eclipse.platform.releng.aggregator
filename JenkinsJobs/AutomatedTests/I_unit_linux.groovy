def config = new groovy.json.JsonSlurper().parseText(readFileFromWorkspace('JenkinsJobs/JobDSL.json'))
def STREAMS = config.Streams
def JAVA_VERSIONS = ['17', '21', '23']

for (STREAM in STREAMS){
for (JAVA_VERSION in JAVA_VERSIONS){
  def MAJOR = STREAM.split('\\.')[0]
  def MINOR = STREAM.split('\\.')[1]

  pipelineJob('AutomatedTests/ep' + MAJOR + MINOR + 'I-unit-linux-x86_64-java' + JAVA_VERSION){
    description('Run Eclipse SDK Tests for the platform implied by this job\'s name')
    parameters { // Define parameters in job configuration to make them available from the very first build onwards
      stringParam('buildId', null, 'Build Id to test (such as I20240611-1800, N20120716-0800).')
    }

    definition {
      cps {
        sandbox()
        script('''
pipeline {
  options {
    timeout(time: 600, unit: 'MINUTES')
    timestamps()
    buildDiscarder(logRotator(numToKeepStr:'5'))
  }
  agent {
    kubernetes {
      label 'centos-unitpod''' + JAVA_VERSION + ''''
      defaultContainer 'custom'
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: "jnlp"
    resources:
      limits:
        memory: "2048Mi"
        cpu: "2000m"
      requests:
        memory: "512Mi"
        cpu: "1000m"
  - name: "custom"
    image: "eclipse/platformreleng-centos-gtk3-metacity:8"
    imagePullPolicy: "Always"
    resources:
      limits:
        memory: "4096Mi"
        cpu: "1000m"
      requests:
        # memory needs to be at least 1024Mi, see https://gitlab.eclipse.org/eclipsefdn/helpdesk/-/issues/2478
        memory: "1024Mi"
        cpu: "1000m"
    securityContext:
      privileged: false
    tty: true
    command:
    - cat
    volumeMounts:
    - mountPath: "/opt/tools"
      name: "volume-0"
      readOnly: false
    workingDir: "/home/jenkins/agent"
  nodeSelector: {}
  restartPolicy: "Never"
  volumes:
  - name: "volume-0"
    persistentVolumeClaim:
      claimName: "tools-claim-jiro-releng"
      readOnly: true
  - configMap:
      name: "known-hosts"
    name: "volume-1"
  - emptyDir:
      medium: ""
    name: "workspace-volume"
  - emptyDir:
      medium: ""
    name: "volume-3"
"""
    }
  }

  stages {
      stage('Run tests'){
          environment {
              // Declaring a jdk and ant the usual way in the 'tools' section, because of unknown reasons, breaks the usage of system commands like xvnc, pkill and sh
              JAVA_HOME = tool(type:'jdk', name:'openjdk-jdk''' + JAVA_VERSION + '''-latest')
              ANT_HOME = tool(type:'ant', name:'apache-ant-latest')
              PATH = "${JAVA_HOME}/bin:${ANT_HOME}/bin:${PATH}"
              ANT_OPTS = "-Djava.io.tmpdir=${WORKSPACE}/tmp -Djava.security.manager=allow"
          }
          steps {
              container ('custom'){
                  xvnc(useXauthority: true) {
                      sh \'\'\'#!/bin/bash -x
                        
                        buildId=$(echo $buildId|tr -d ' ')
                        export LANG=en_US.UTF-8
                        cat /etc/*release
                        echo "whoami:  $(whoami)"
                        echo "uname -a: $(uname -a)"
                        
                        # 0002 is often the default for shell users, but it is not when ran from
                        # a cron job, so we set it explicitly, to be sure of value, so releng group has write access to anything
                        # we create on shared area.
                        oldumask=$(umask)
                        umask 0002
                        echo "umask explicitly set to 0002, old value was $oldumask"
                        
                        # we want java.io.tmpdir to be in $WORKSPACE, but must already exist, for Java to use it.
                        mkdir -p tmp
                        
                        curl -o getEBuilder.xml https://download.eclipse.org/eclipse/relengScripts/production/testScripts/hudsonBootstrap/getEBuilder.xml
                        curl -o buildproperties.shsource https://download.eclipse.org/eclipse/downloads/drops4/${buildId}/buildproperties.shsource
                        source buildproperties.shsource
                        
                        echo JAVA_HOME: $JAVA_HOME
                        echo ANT_HOME: $ANT_HOME
                        echo PATH: $PATH
                        
                        env 1>envVars.txt 2>&1
                        ant -diagnostics 1>antDiagnostics.txt 2>&1
                        java -XshowSettings -version 1>javaSettings.txt 2>&1
                        
                        ant -f getEBuilder.xml -DbuildId=${buildId} -DeclipseStream=${STREAM} -DEBUILDER_HASH=${EBUILDER_HASH} \\
                          -DdownloadURL=https://download.eclipse.org/eclipse/downloads/drops4/${buildId} \\
                          -Dosgi.os=linux -Dosgi.ws=gtk -Dosgi.arch=x86_64 \\
                          -DtestSuite=all
                        # For smaller test-suites see: https://github.com/eclipse-platform/eclipse.platform.releng.aggregator/blob/be721e33c916b03c342e7b6f334220c6124946f8/production/testScripts/configuration/sdk.tests/testScripts/test.xml#L1893-L1903
                      \'\'\'
                  }
              }
              archiveArtifacts '**/eclipse-testing/results/**, **/eclipse-testing/directorLogs/**, *.properties, *.txt'
              junit keepLongStdio: true, testResults: '**/eclipse-testing/results/xml/*.xml'
              build job: 'Releng/ep-collectResults', wait: false, parameters: [
                string(name: 'triggeringJob', value: "${JOB_BASE_NAME}"),
                string(name: 'buildURL', value: "${BUILD_URL}"),
                string(name: 'buildID', value: "${params.buildId}")
              ]
          }
      }
  }
}
        ''')
      }
    }
  }
}
}
