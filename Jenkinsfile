pipeline {
    agent any
    tools {
    maven 'mvn-3.6.0'
    }
    stages {
        stage ('Initialize') {
            steps {
                sh '''
                    echo "PATH = ${PATH}"
                    echo "M2_HOME = ${M2_HOME}"
                '''
            }
        }

        stage ('Build') {
            steps {
                sh 'mvn clean install -DskipTests' 
            }
            post {
                success {
                    sh 'mvn test -P jmh-benchmark' 
                }
            }
        }
    }
}
