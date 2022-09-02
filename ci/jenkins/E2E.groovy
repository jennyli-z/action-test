int total_timeout_minutes = 60*3
pipeline {
    agent {
        kubernetes {
            inheritFrom 'default'
            yamlFile 'jenkins/knowhere/test/pod/ci-cpu-e2e.yaml'
            defaultContainer 'main'
        }
    }
    options {
        timeout(time: total_timeout_minutes, unit: 'MINUTES')
        buildDiscarder logRotator(artifactDaysToKeepStr: '30')
        parallelsAlwaysFailFast()
        // disableConcurrentBuilds(abortPrevious: true)
        preserveStashes(buildCount: 10)
    }
    stages {
           stage("Build"){
                steps {
                    container("build"){
                    script{
                        checkout([$class: 'GitSCM', branches: [[name: '*/main']], extensions: [], 
                        userRemoteConfigs: [[credentialsId: 'milvus-ci', url: 'https://github.com/milvus-io/knowhere.git']]]) 
                        def date = sh(returnStdout: true, script: 'date +%Y%m%d').trim()
                        def gitShortCommit = sh(returnStdout: true, script: "echo ${env.GIT_COMMIT} | cut -b 1-7 ").trim()  
                        version="main.${date}.${gitShortCommit}"
                        sh "./build.sh -d -g -u -t Release"
                        knowhere_wheel="knowhere-${version}-cp38-cp38-linux_x86_64.whl"
                        sh "cd python  && VERSION=${version} python3 setup.py bdist_wheel"
                        dir('python'){
                        archiveArtifacts artifacts: "dist/${knowhere_wheel}", followSymlinks: false
                        }
                            // stash knowhere info for rebuild E2E Test only
                        sh "echo ${knowhere_wheel} > knowhere.txt"
                        stash includes: 'knowhere.txt', name: 'knowhereWheel'
                    }
                }  
            }  
        }
        stage("Test"){
            steps {
                script{
                   if ("${knowhere_wheel}"==''){
                        dir ("knowhereWheel"){
                            try{
                                unstash 'knowhereWheel'
                               knowhere_wheel=sh(returnStdout: true, script: 'cat knowhere.txt | tr -d \'\n\r\'')
                            }catch(e){
                                error "No knowhereWheel info remained ,please rerun build to build new package."
                            }
                        }
                    }
                    checkout([$class: 'GitSCM', branches: [[name: '*/main']], extensions: [], 
                    userRemoteConfigs: [[credentialsId: 'milvus-ci', url: 'https://github.com/milvus-io/knowhere-test.git']]])   
                    dir('tests'){
                      unarchive mapping: ["dist/${knowhere_wheel}": "${knowhere_wheel}"]
                      sh "ls -lah"
                      sh "pip3 install  ${knowhere_wheel} \
                          && pip3 install -r requirements.txt --timeout 30 --retries 6 &&  pytest -v -m 'L0 or L2' --data_type glove"
                    }

                }
            }
            post{
                always {
                    script{
                        sh 'cp /tmp/knowhere_ci.log knowhere_ci.log'
                        archiveArtifacts artifacts: 'knowhere_ci.log', followSymlinks: false
                    }
                }
                
            }
        }
        
    }
    post{
        unsuccessful {
            script{
                withCredentials([string(credentialsId: 'knw_feishu_webhook', variable: 'KNW_FEISHU_URL')]) {
                    sh './scripts/feishu.sh'
                }
            }
        }
    }
}
