#!/usr/bin/env groovy
// Licensed to Elasticsearch B.V. under one or more contributor
// license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright
// ownership. Elasticsearch B.V. licenses this file to you under
// the Apache License, Version 2.0 (the "License"); you may
// not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
@Library('apm@current') _

pipeline {
  agent none
  environment {
    REPO = "azure-vm-extension"
    NOTIFY_TO = credentials('notify-to')
    PIPELINE_LOG_LEVEL = 'INFO'
    LANG = "C.UTF-8"
    LC_ALL = "C.UTF-8"
  }
  options {
    buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5', daysToKeepStr: '7'))
    timestamps()
    ansiColor('xterm')
    disableResume()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    timeout(time: 2, unit: 'HOURS')
    disableConcurrentBuilds()
  }
  triggers {
    cron('H H(5-6) * * 1-5')
  }
  parameters {
    booleanParam(name: 'skipDestroy', defaultValue: "false", description: "Whether to skip the destroy of the cluster and terraform.")
  }
  stages {
    stage('ITs') {
      options { skipDefaultCheckout() }
      failFast false
      matrix {
        agent { label 'ubuntu-20' }
        axes {
          axis {
            name 'ELASTIC_STACK_VERSION'
            // The below line is part of the bump release automation
            // if you change anything please modifies the file
            // .ci/bump-stack-release-version.sh
            values '8.0.0-SNAPSHOT', '7.14.0-SNAPSHOT', '7.13.2'
          }
        }
        environment {
          HOME = "${env.WORKSPACE}"
          PATH = "${env.HOME}/bin:${env.PATH}"
        }
        stages {
          stage('Checkout'){
            steps {
              deleteDir()
              checkout scm
            }
          }
          stage('Create cluster'){
            options { skipDefaultCheckout() }
            steps {
              withGithubNotify(context: "Create Cluster ${ELASTIC_STACK_VERSION}") {
                withVaultEnv(){
                  sh(label: 'Deploy Cluster', script: 'make -C .ci create-cluster')
                }
              }
            }
            post {
              failure {
                destroyCluster()
              }
            }
          }
          stage('Prepare tools') {
            options { skipDefaultCheckout() }
            steps {
              withCloudEnv() {
                sh(label: 'Prepare tools', script: 'make -C .ci prepare')
              }
            }
            post {
              failure {
                destroyCluster()
              }
            }
          }
          stage('Terraform') {
            options { skipDefaultCheckout() }
            steps {
              withGithubNotify(context: "Terraform ${ELASTIC_STACK_VERSION}") {
                withCloudEnv() {
                  withAzEnv() {
                    sh(label: 'Run terraform plan', script: 'make -C .ci terraform-run')
                  }
                }
              }
            }
            post {
              failure {
                destroyTerraform()
                destroyCluster()
              }
            }
          }
          stage('Validate') {
            options { skipDefaultCheckout() }
            steps {
              withGithubNotify(context: "Validate ${ELASTIC_STACK_VERSION}") {
                withValidationEnv() {
                  sh(label: 'Validate', script: 'make -C .ci validate')
                }
              }
            }
            post {
              always {
                destroyTerraform()
                destroyCluster()
              }
            }
          }
        }
      }
    }
  }
  post {
    cleanup {
      notifyBuildResult(prComment: true)
    }
  }
}

def destroyCluster( ) {
  if (params.skipDestroy) {
    echo 'Skipped the destroy cluster step'
    return
  }
  withVaultEnv(){
    sh(label: 'Destroy Cluster', script: 'make -C .ci destroy-cluster')
  }
}

def destroyTerraform( ) {
  if (params.skipDestroy) {
    echo 'Skipped the destroy terraform step'
    return
  }
  withCloudEnv() {
    withAzEnv() {
      sh(label: 'Destroy terraform plan', script: 'make -C .ci terraform-destroy')
    }
  }
}

def withVaultEnv(Closure body){
  getVaultSecret.readSecretWrapper {
    withMatrixEnv() {
      withEnvMask(vars: [
        [var: 'VAULT_ADDR', password: env.VAULT_ADDR],
        [var: 'VAULT_ROLE_ID', password: env.VAULT_ROLE_ID],
        [var: 'VAULT_SECRET_ID', password: env.VAULT_SECRET_ID],
        [var: 'VAULT_AUTH_METHOD', password: 'approle'],
        [var: 'VAULT_AUTHTYPE', password: 'approle']
      ]){
        body()
      }
    }
  }
}

def withValidationEnv(Closure body) {
  withMatrixEnv() {
    def props = getVaultSecret(secret: "secret/observability-team/ci/test-clusters/${env.CLUSTER_NAME}/k8s-elasticsearch")
    if (props?.errors) {
      error "withValidationEnv: Unable to get credentials from the vault: ${props.errors.toString()}"
    }

    def esJson = props?.data.value
    def es = toJSON(esJson)
    def es_url = es.url
    def username = es.username
    def password = es.password
    if(es_url == null || username == null || password == null){
      error "withValidationEnv: was not possible to get the authentication info."
    }
    withEnvMask(vars: [
      [var: 'ES_URL', password: es_url],
      [var: 'ES_USERNAME', password: username],
      [var: 'ES_PASSWORD', password: password],
      [var: 'VM_NAME', password: "${env.TF_VAR_vmName}"]
    ]){
      body()
    }
  }
}

def withCloudEnv(Closure body) {
  withMatrixEnv() {
    def props = getVaultSecret(secret: "secret/observability-team/ci/test-clusters/${env.CLUSTER_NAME}/ec-deployment")
    if (props?.errors) {
      error "withCloudEnv: Unable to get credentials from the vault: ${props.errors.toString()}"
    }
    def value = props?.data
    def cloud_id = value?.cloud_id
    def username = value?.username
    def password = value?.password
    if(cloud_id == null || username == null || password == null){
      error "withCloudEnv: was not possible to get the authentication info."
    }
    withEnvMask(vars: [
      [var: 'TF_VAR_username', password: username],
      [var: 'TF_VAR_password', password: password],
      [var: 'TF_VAR_cloudId', password: cloud_id]
    ]){
      body()
    }
  }
}

def withAzEnv(Closure body) {
  withMatrixEnv() {
    def props = getVaultSecret(secret: 'secret/observability-team/ci/service-account/azure-vm-extension')
    if (props?.errors) {
      error "withAzEnv: Unable to get credentials from the vault: ${props.errors.toString()}"
    }
    def value = props?.data
    def tenant = value?.tenant
    def username = value?.username
    def password = value?.password
    def subscription = value?.subscription
    if(tenant == null || username == null || password == null || subscription == null){
      error "withAzEnv: was not possible to get the authentication info."
    }
    withEnvMask(vars: [
      [var: 'AZ_USERNAME', password: username],
      [var: 'AZ_PASSWORD', password: password],
      [var: 'AZ_TENANT', password: tenant],
      [var: 'AZ_SUBSCRIPTION', password: subscription]
    ]){
      body()
    }
  }
}

def withMatrixEnv(Closure body) {
  // -S need to avoid clashes between same version for releases and snapshots.
  // 7.13.0-SNAPSHOT and 7.13.0 might clash so let's keep the -S for this.
  def stackVersion = env.ELASTIC_STACK_VERSION.replaceAll('-SNAPSHOT','-S').replaceAll('\\.', '-')
  def uniqueId = "${stackVersion}-${BUILD_ID}-${BRANCH_NAME}"
  withEnv([
    "CLUSTER_NAME=tst-az-${BUILD_ID}-${BRANCH_NAME}-${ELASTIC_STACK_VERSION}",
    'TF_VAR_prefix=' + uniqueId.take(9) + '0',
    'TF_VAR_vmName=' + uniqueId.take(14) + '0'
  ]) {
    echo "CLUSTER_NAME=${CLUSTER_NAME}"
    echo "uniqueId=${uniqueId}"
    body()
  }
}