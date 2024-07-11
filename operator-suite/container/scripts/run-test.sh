#!/usr/bin/env bash

set -e

SCRIPT_DIR=$(dirname "$(readlink -f "${BASH_SOURCE:-$0}")")

### Script arg parse is done by code generated by Argbash and is located in lib/run-test-arg-parser.sh
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/run-test-arg-parser.sh"

function log() {
    if [[ "$1" == "" ]]; then
        echo "$1"
    else
        echo "[$(date "+%Y-%m-%d %H:%M:%S %z")] - $1"
    fi
}

# shellcheck disable=SC2154 
if [[ "${_arg_debug}" == "on" ]]; then
    set -x
    log "Value of --debug: $_arg_debug"
    log "Value of --image-namespace: $_arg_image_namespace"
    log "Value of --image-tag: $_arg_image_tag"
    log "Value of --make-target: $_arg_make_target"
    log "Value of --namespace: $_arg_namespace"
    log "Value of --make-envvar: ${_arg_make_envvar[*]}"
    log "Value of --kubeconfig: $_arg_kubeconfig"
    log "Value of --test-completion-wait-check: $_arg_test_completion_wait_check"
fi

if [[ -z "${_arg_kubeconfig}" ]]; then
    export KUBE_CONFIG="${KUBECONFIG:-${HOME}/.kube/config}"
else
    export KUBE_CONFIG="${_arg_kubeconfig}"
fi

export NAMESPACE="${_arg_namespace}"
export IMAGE="quay.io/${_arg_image_namespace}/claire:${_arg_image_tag}"
export MAKE_TARGET="${_arg_make_target}"

log ""
log "Creating Namespace ${NAMESPACE}"
yq e '.metadata.name = strenv(NAMESPACE)' "${SCRIPT_DIR}/yaml/010-namespace.yaml" | tee >(kubectl apply -f -)
sleep 5

log ""
log "Creating secret for kubeconfig from ${KUBE_CONFIG}"
mkdir -p /tmp/claire.$$ 
cp "${KUBE_CONFIG}" /tmp/claire.$$/config
kubectl -n "${NAMESPACE}" create secret generic claire-kubeconfig --from-file /tmp/claire.$$/config || true      
sleep 5

log ""
log "Creating config map for run script"
yq '(.metadata.namespace = strenv(NAMESPACE)) | (.data."run.sh" |= envsubst)' "${SCRIPT_DIR}/yaml/020-configmap.yaml" | tee >(kubectl apply -f -)
sleep 5

log ""
log "Creating test suite pod"
cp "${SCRIPT_DIR}/yaml/030-pod.yaml" "${SCRIPT_DIR}/yaml/030-pod.yaml.$$"
_arg_make_envvar+=('MVN_TEST_ADDITIONAL_ARGS=-Dfailsafe.rerunFailingTestsCount=2' 'TEST_LOG_LEVEL=DEBUG')
if [[ "${#_arg_make_envvar[@]}" -gt 0  ]]; then
    for i in "${_arg_make_envvar[@]}"; do
        KEY="$(echo "${i}" | cut -d"=" -f1)"
        export KEY;
        VALUE="$(echo "${i}" | cut -d"=" -f2-)"
        export VALUE;
        yq -i e '(.spec.containers |=  map(select(.name == "claire-test-suite").env += [{"name": strenv(KEY), "value": strenv(VALUE)}]))' "${SCRIPT_DIR}/yaml/030-pod.yaml.$$"
    done
fi
yq -i e '(.metadata.namespace = strenv(NAMESPACE))' "${SCRIPT_DIR}/yaml/030-pod.yaml.$$"
yq -i e '(.spec.containers[].image = strenv(IMAGE))' "${SCRIPT_DIR}/yaml/030-pod.yaml.$$"
tee >(kubectl apply -f -) < "${SCRIPT_DIR}/yaml/030-pod.yaml.$$"
rm -rf "${SCRIPT_DIR}/yaml/030-pod.yaml.$$"
sleep 5

log ""
log "Waiting claire-test-suite pod reach Running state"
set +e
if kubectl wait -n "${NAMESPACE}" --for=jsonpath='{.status.phase}'='Running' pod/claire-test-suite --timeout=300s ; then
    log ""
    log "Waiting execution to finish"
    set +e
    while ! kubectl -n "${NAMESPACE}" exec claire-test-suite -- /bin/ls /app/test-results/tests.execution.completed > /dev/null 2>&1; do
    log "Execution not completed. Waiting for ${_arg_test_completion_wait_check} seconds before check again"
    sleep "${_arg_test_completion_wait_check}"
    done
    set -e

    log ""
    log "Copying test results, logs and additional files to ${PWD}/test-results"
    mkdir -p "${PWD}/test-results/operator-suite"
    # shellcheck disable=SC2016
    kubectl exec -n "${NAMESPACE}" claire-test-suite -- bash -c 'tar cf - $(find /app/test-results /app/operator-suite/test-logs /app/operator-suite/test-tmp 2>/dev/null)' | tar --strip-components 2 -xf - -C test-results
else
    log "Error on getting claire-test-suite pod into Running state. Dumping logs"
    log ""
    log "Getting events"
    kubectl -n "${NAMESPACE}" get events
    log ""
    log "Gettings pod logs"
    kubectl -n "${NAMESPACE}" logs claire-test-suite
fi
set -e

log ""
log "Deleting Namespace ${NAMESPACE}"
yq e '.metadata.name = strenv(NAMESPACE)' "${SCRIPT_DIR}/yaml/010-namespace.yaml" | tee >(kubectl delete -f -)
