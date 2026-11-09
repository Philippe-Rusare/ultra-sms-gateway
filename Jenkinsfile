// CI/CD of Ultra SMS Gateway: tests, SonarQube analysis, Docker image on Docker Hub, optional production deployment.
// Required Jenkins credentials: sonarqube-token, dockerhub-credentials, telegram-bot-token, telegram-chat-id.
// See docs/ci.md.

def waitForSonarScannerApi() {
    retry(3) {
        def sonarApiStatus = sh(
            returnStatus: true,
            script: '''
                curl --fail --silent --show-error \
                  --header "Authorization: Bearer ${SONAR_TOKEN}" \
                  "${SONAR_HOST_URL}/api/v2/analysis/version" >/dev/null
            '''
        )

        if (sonarApiStatus != 0) {
            sleep time: 10, unit: 'SECONDS'
            error 'SonarQube scanner API is temporarily unavailable.'
        }
    }
}

def sendTelegramReport(String statusIcon, String statusLabel) {
    def duration = currentBuild.durationString.replace(' and counting', '').trim()
    def failedStage = env.FAILED_STAGE?.trim() ?: 'Checkout SCM or pipeline initialization'
    def failureDetails = statusLabel == 'BUILD SUCCESS' ? '' : "\n❗ Étape en cause: ${failedStage}\n"
    def telegramMessage = """${statusIcon} Ultra SMS Gateway CI/CD

${statusLabel}${failureDetails}
📦 Projet: ${env.JOB_NAME}
🔢 Build: #${env.BUILD_NUMBER}
🌿 Branche: ${env.GIT_BRANCH ?: 'unknown'}
👤 Auteur: ${env.GIT_AUTHOR ?: 'unknown'}
📝 Commit: ${env.GIT_MESSAGE ?: 'unknown'}
🔖 SHA: ${env.GIT_COMMIT ?: 'unknown'}

🐳 Image: ${env.PUBLISHED_IMAGE ?: 'non publiée'}
✅ Tests: ${env.TESTS_PASSED == 'true' ? 'validés' : 'non validés'}
📊 SonarQube: ${env.ANALYSIS_PASSED == 'true' ? 'validé' : 'non validé'}
🚀 Déploiement: ${env.DEPLOYED == 'true' ? 'effectué' : 'non effectué'}

⏱ Durée: ${duration}
🔗 Jenkins: ${env.BUILD_URL}
"""

    try {
        withCredentials([
            string(credentialsId: 'telegram-bot-token', variable: 'BOT_TOKEN'),
            string(credentialsId: 'telegram-chat-id', variable: 'CHAT_ID')
        ]) {
            withEnv(["TELEGRAM_MESSAGE=${telegramMessage}"]) {
                def telegramExitCode = sh(
                    returnStatus: true,
                    script: '''
                        curl --fail --silent --show-error --request POST "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
                          --data-urlencode "chat_id=${CHAT_ID}" \
                          --data-urlencode "text=${TELEGRAM_MESSAGE}"
                    '''
                )

                if (telegramExitCode != 0) {
                    echo "Telegram notification failed with exit code ${telegramExitCode}."
                }
            }
        }
    } catch (Exception exception) {
        echo "Telegram notification could not be sent: ${exception.message}"
    }
}

pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    parameters {
        booleanParam(
            name: 'DEPLOY_TO_PRODUCTION',
            defaultValue: false,
            description: 'Déploie l’image publiée en production après confirmation manuelle.'
        )
        string(
            name: 'IMAGE_VERSION',
            defaultValue: '',
            trim: true,
            description: 'Version Docker optionnelle, par exemple 0.2.0. Vide : version automatique BUILD-SHA.'
        )
    }

    environment {
        SONAR_MAVEN_PLUGIN_VERSION = '5.7.0.6970'
        SONAR_HOST_URL = 'http://sonarqube:9000'
        SONAR_PROJECT_KEY = 'ultra-sms-gateway'
        IMAGE_NAME = 'ultra-sms-gateway'
        DEPLOY_DIRECTORY = '/opt/ultra-sms-gateway'
        DOCKER_BUILDKIT = '1'
    }

    stages {
        stage('Prepare execution') {
            steps {
                script {
                    env.FAILED_STAGE = 'Prepare execution'
                    env.TESTS_PASSED = 'false'
                    env.ANALYSIS_PASSED = 'false'
                    env.DEPLOYED = 'false'

                    def requestedImageVersion = params.IMAGE_VERSION?.trim()

                    if (requestedImageVersion && !(requestedImageVersion ==~ /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/)) {
                        error 'IMAGE_VERSION must be a valid Docker tag: up to 128 letters, digits, underscores, dots, or hyphens.'
                    }

                    env.IMAGE_TAG = requestedImageVersion ?: "${env.BUILD_NUMBER}-${sh(returnStdout: true, script: 'git rev-parse --short=7 HEAD').trim()}"
                    env.GIT_AUTHOR = sh(returnStdout: true, script: "git log -1 --pretty=format:'%an'").trim()
                    env.GIT_MESSAGE = sh(returnStdout: true, script: "git log -1 --pretty=format:'%s'").trim()
                    env.GIT_COMMIT = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    env.GIT_BRANCH = env.BRANCH_NAME?.trim() ?: sh(
                        returnStdout: true,
                        script: "git name-rev --name-only --refs='refs/remotes/origin/*' HEAD | sed -n '1s#^origin/##p'"
                    ).trim()
                    env.IS_MAIN_BRANCH = env.GIT_BRANCH == 'main' ? 'true' : 'false'

                    if (params.DEPLOY_TO_PRODUCTION && env.IS_MAIN_BRANCH != 'true') {
                        error "Only the main branch can be deployed to production (current branch: ${env.GIT_BRANCH})."
                    }

                    currentBuild.description = "Image: ${env.IMAGE_TAG}; Branche: ${env.GIT_BRANCH}"
                }
            }
        }

        stage('Test') {
            steps {
                script {
                    env.FAILED_STAGE = 'Test'
                    sh './mvnw --batch-mode --no-transfer-progress verify'
                    env.TESTS_PASSED = 'true'
                }
            }
        }

        stage('Analyze') {
            steps {
                script {
                    env.FAILED_STAGE = 'Analyze'
                    withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                        waitForSonarScannerApi()
                        sh """
                            ./mvnw --batch-mode --no-transfer-progress \
                              org.sonarsource.scanner.maven:sonar-maven-plugin:${env.SONAR_MAVEN_PLUGIN_VERSION}:sonar \
                              -Dsonar.host.url=${env.SONAR_HOST_URL} \
                              -Dsonar.token=\$SONAR_TOKEN \
                              -Dsonar.projectKey=${env.SONAR_PROJECT_KEY} \
                              -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
                              -Dsonar.qualitygate.wait=true
                        """
                    }
                    env.ANALYSIS_PASSED = 'true'
                }
            }
        }

        stage('Build and publish image') {
            // Only main publishes; other branches stop after tests and analysis.
            when {
                expression { env.IS_MAIN_BRANCH == 'true' }
            }
            steps {
                script {
                    env.FAILED_STAGE = 'Build and publish image'
                    withCredentials([usernamePassword(
                        credentialsId: 'dockerhub-credentials',
                        usernameVariable: 'DOCKERHUB_USERNAME',
                        passwordVariable: 'DOCKERHUB_PASSWORD'
                    )]) {
                        def repository = "${env.DOCKERHUB_USERNAME}/${env.IMAGE_NAME}"
                        sh '''
                            printf '%s' "$DOCKERHUB_PASSWORD" | \
                              docker login --username "$DOCKERHUB_USERNAME" --password-stdin
                        '''
                        sh "docker build --tag ${repository}:${env.IMAGE_TAG} --tag ${repository}:latest ."
                        sh "docker push ${repository}:${env.IMAGE_TAG}"
                        sh "docker push ${repository}:latest"
                        env.PUBLISHED_IMAGE = "${repository}:${env.IMAGE_TAG}"
                    }
                }
            }
        }

        stage('Deploy to production') {
            when {
                expression { params.DEPLOY_TO_PRODUCTION && env.PUBLISHED_IMAGE?.trim() ? true : false }
            }
            steps {
                script {
                    env.FAILED_STAGE = 'Confirm production deployment'
                    input message: "Deploy ${env.PUBLISHED_IMAGE} to production?", ok: 'Deploy'

                    env.FAILED_STAGE = 'Deploy to production'
                    // The production .env (passwords) lives only on the server, in DEPLOY_DIRECTORY; it is never copied from Git.
                    sh """
                        set -eu
                        test -f ${env.DEPLOY_DIRECTORY}/.env
                        cp docker-compose.yml ${env.DEPLOY_DIRECTORY}/docker-compose.yml
                        cd ${env.DEPLOY_DIRECTORY}
                        export GATEWAY_IMAGE=${env.PUBLISHED_IMAGE}
                        docker compose pull gateway
                        docker compose up --detach --no-build
                    """
                    env.DEPLOYED = 'true'
                }
            }
        }
    }

    post {
        success {
            script {
                sendTelegramReport('✅', 'BUILD SUCCESS')
            }
        }
        failure {
            script {
                sendTelegramReport('❌', 'BUILD FAILED')
            }
        }
        unstable {
            script {
                sendTelegramReport('⚠️', 'BUILD UNSTABLE')
            }
        }
        aborted {
            script {
                sendTelegramReport('⏹️', 'BUILD ABORTED')
            }
        }
        always {
            script {
                if (env.WORKSPACE?.trim()) {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                    archiveArtifacts allowEmptyArchive: true, artifacts: 'target/*.jar', fingerprint: true
                    sh 'docker logout || true'
                    cleanWs()
                }
            }
        }
    }
}
