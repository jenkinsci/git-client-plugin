pipeline {
    agent {
    	docker {
            image 'maven:3-alpine' 
            args '-v /root/.m2:/root/.m2' 
        }
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
