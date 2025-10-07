pipeline {
    agent any
    stages {
        stage('Report BUILD_TAG') {
            steps {
                echo "env.BUILD_TAG is ${env.BUILD_TAG}"
                script {
                    if (env.BUILD_TAG.contains('%2F') || env.BUILD_TAG.contains('/')) {
                        // Mark build unstable if BUILD_TAG contains '/' or escaped '/'
                        unstable('Invalid BUILD_TAG value')
                    }
                }
            }
        }
    }
}
