def call(body) {

  def cfg = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = cfg
  body()

  def agent = (cfg.agent != null) ? cfg.agent : ''

  node(agent) {
    // set current step for the notification handler
    def pipelineStep = "start"
    def repositoryUrl = scm.getUserRemoteConfigs()[0].getUrl()
    def config = processPhpConfig(cfg, env.BRANCH_NAME, env.BUILD_NUMBER, repositoryUrl)

    try {
      // https://jenkins.io/doc/pipeline/steps/workflow-scm-step/
      stage('Checkout') {
        pipelineStep = "checkout"
        if (!fileExists('repo')){ new File('repo').mkdir() }
        dir('repo') { checkout scm }

        // create a changelog
        def changelog = getChangelog()
        echo(changelog)
        writeFile(file: "./changelog.txt", text: changelog)
      }

      // start of Build stage
      stage('Build') {
        pipelineStep = "build"
        createPhpComposeBuildEnv(config, './build.json') // create docker-compose file
        sh(script: "docker-compose -f ./build.json build")
      }
      // end of Build stage

      // start of Docker push image stage
      stage('Push image') {
        pipelineStep = "push image"
        sh(script: "gcloud auth configure-docker --configuration ${config.envDetails.gcpProjectId}")
        sh(script: "docker push ${config.dockerImageTag}")
      }
      // end of Docker push image stage

      // start of Deploy stage
      stage('Deploy') {
        pipelineStep = "deploy"

        // if specified, obtain secrets
        def secretData
        if(config.secretsInjection) {
          // get secrets from Vault
          secretData = createPhpSecretsManifest(config)
        } else {
          echo "Skipping injection of credentials"
        }

        // create helm values file
        def helmValuesJson = createPhpHelmValues(config, secretData)
        writeFile(file: "./values.json", text: helmValuesJson)

        // try to create yaml file from template first
        // this checks whether values.yaml contains required fields
        def tmplOut = config.debugMode ? "./tmpl.out.yaml"  : "/dev/null"
        sh(script: "helm template -f ./values.json ${config.helmChart} -n ${config.helmReleaseName} > ${tmplOut}")

        // upgrade or install release
        def deployCommand = "helm upgrade " +
          "--install " +
          "--kubeconfig ${config.kubeConfigPath} " +
          "-f ./values.json " +
          "--namespace ${config.envDetails.k8sNamespace} " +
          "${config.helmReleaseName} " +
          "${config.helmChart} "

        sh(script: deployCommand + " --dry-run")
        if(!config.dryRun) { sh(script: deployCommand) }
      }
      // end of Deploy stage

    } catch(err) {
      currentBuild.result = "FAILURE"
      println(err.toString());
      println(err.getMessage());
      println(err.getStackTrace());
      throw err
    } finally {
      // remove all containers
      sh(script: 'docker-compose -f build.json rm -s -f')
      if(config.documentation) { sh(script: 'docker-compose -f documentation.json rm -s -f') }
      if(config.testConfig) { sh(script: 'docker-compose -f test.json rm -s -f') }

      // sometimes you need to check these files you know
      if(!config.debugMode) {
        sh(script: 'rm -rf ./build.json')
        sh(script: 'rm -rf ./secrets')
        sh(script: 'rm -rf ./values.json')
      }

      // send slack notification
      if(config.slackChannel) {
        notifyPhpBuild(
          buildStatus: currentBuild.result,
          buildType: 'Build',
          channel: config.slackChannel,
          reason: pipelineStep
        )
      }
    }
  }
}
