// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Mandatory configuration
    def gitUrl = config["git"]
    def svnUrl = config["svn"]

    // Optional configuration
    def projectName = config["name"] ?: env.JOB_BASE_NAME
    def dockerfile = config["dockerfile"] ?: "oss-fuzz/targets/$projectName/Dockerfile"
    def sanitizers = config["sanitizers"] ?: ["address"]
    def checkoutDir = config["checkoutDir"] ?: projectName
    def dockerContextDir = config["dockerContextDir"]

    def date = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        .format(java.time.LocalDateTime.now())

    node {
        def workspace = pwd()
        // def uid = sh(returnStdout: true, script: 'id -u $USER').trim()
        def uid = 0 // TODO: try to make $USER to work
        echo "using uid $uid"

        def srcmapFile = "$workspace/srcmap.json"
        def dockerTag = "ossfuzz/$projectName"
        echo "Building $dockerTag"

        sh "rm -rf $workspace/out"
        sh "mkdir -p $workspace/out"

        stage("docker image") {
            dir('oss-fuzz') {
                git url: "https://github.com/google/oss-fuzz.git"
            }

            if (gitUrl != null) {
                dir(checkoutDir) {
                    git url: gitUrl
                }
            }
            if (svnUrl != null) {
                dir(checkoutDir) {
                    svn url: svnUrl
                }
            }

            if (dockerContextDir == null) {
                dockerContextDir = new File(dockerfile)
                    .getParentFile()
                    .getPath();
            }

            sh "docker build --no-cache -t $dockerTag -f $dockerfile $dockerContextDir"
            sh "docker run --rm $dockerTag srcmap > $srcmapFile"
            sh "cat $srcmapFile"
        } // stage("docker image")

        for (int i = 0; i < sanitizers.size(); i++) {
            def sanitizer = sanitizers[i]
            dir(sanitizer) {
                def out = "$workspace/out/$sanitizer"
                def junit_reports = "$workspace/junit_reports/$sanitizer"
                sh "mkdir -p $out"
                sh "mkdir -p $junit_reports"
                stage("$sanitizer sanitizer") {
                    // Run image to produce fuzzers
                    sh "docker run --rm --user $uid -v $out:/out -v $junit_reports:/junit_reports -e SANITIZER_FLAGS=\"-fsanitize=$sanitizer\" -t $dockerTag test"
                    sh "ls -al $junit_reports/"
                }
            }
        }

        stage("uploading") {
            step([$class: 'JUnitResultArchiver', testResults: 'junit_reports/**/*.xml'])
            dir('out') {
                for (int i = 0; i < sanitizers.size(); i++) {
                    def sanitizer = sanitizers[i]
                    dir (sanitizer) {
                        def zipFile = "$projectName-$sanitizer-${date}.zip"
                        sh "zip -j $zipFile *"
                        sh "gsutil cp $zipFile gs://clusterfuzz-builds/$projectName/"
                        def stampedSrcmap = "$projectName-$sanitizer-${date}.srcmap.json"
                        sh "cp $srcmapFile $stampedSrcmap"
                        sh "gsutil cp $stampedSrcmap gs://clusterfuzz-builds/$projectName/"
                    }
                }
            }
        } // stage("uploading")

        stage("pushing image") {
            docker.withRegistry('', 'docker-login') {
                docker.image(dockerTag).push()
            }
        } // stage("pushing image")
    } // node
}  // call

return this;
