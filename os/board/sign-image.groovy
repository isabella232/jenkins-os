#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        string(name: 'MANIFEST_REF',
               defaultValue: 'refs/tags/'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

stage('Wait') {
    def version = params.MANIFEST_REF?.startsWith('refs/tags/v') ? params.MANIFEST_REF.substring(11) : ''
    slackSend color: '#C0C0C0', message: """The ${params.BOARD} ${version ?: "UNKNOWN"} build is waiting for the boot loader files to be signed for Secure Boot and uploaded to https://console.cloud.google.com/storage/browser/builds.release.core-os.net/signed/boards/${params.BOARD}/${version} to continue.\n
When all boot loader files are uploaded, go to ${BUILD_URL}input and proceed with the build."""
    input 'Waiting for the signed UEFI binaries to be ready...'
}

stage('Amend') {
    withCredentials([
        [$class: 'FileBinding',
         credentialsId: 'buildbot-official.2E16137F.subkey.gpg',
         variable: 'GPG_SECRET_KEY_FILE'],
        [$class: 'FileBinding',
         credentialsId: 'jenkins-coreos-systems-write-5df31bf86df3.json',
         variable: 'GOOGLE_APPLICATION_CREDENTIALS']
    ]) {
        withEnv(["MANIFEST_NAME=${params.MANIFEST_NAME}",
                 "MANIFEST_REF=${params.MANIFEST_REF}",
                 "MANIFEST_URL=${params.MANIFEST_URL}",
                 "BOARD=${params.BOARD}"]) {
            sh '''#!/bin/bash -ex

sudo rm -rf gce.properties src tmp

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

./bin/cork update --create --downgrade-replace --verify --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "${MANIFEST_REF}" \
                  --manifest-name "${MANIFEST_NAME}"

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  ./bin/cork enter --experimental -- "${script}" "$@"
}

enter() {
  ./bin/cork enter --experimental -- "$@"
}

source .repo/manifests/version.txt
export COREOS_BUILD_ID

# Set up GPG for signing images
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap "rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import "${GPG_SECRET_KEY_FILE}"

kernel=coreos_production_image.vmlinuz
grub=coreos_production_image.grub
shim=coreos_production_image.shim
[[ ${BOARD} == amd64-usr ]] || shim=

DOWNLOAD=gs://builds.release.core-os.net  # /signed, /unsigned
UPLOAD=gs://builds.release.core-os.net  # /alpha, /beta, /stable

mkdir -p src tmp
./bin/cork download-image --root="${DOWNLOAD}/unsigned/boards/${BOARD}/${COREOS_VERSION}" \
                          --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
                          --cache-dir=./src \
                          --platform=qemu
img=src/coreos_production_image.bin
[[ "${img}.bz2" -nt "${img}" ]] && enter lbunzip2 -k -f "/mnt/host/source/${img}.bz2"

enter gsutil cp \
    ${kernel:+"${DOWNLOAD}/signed/boards/${BOARD}/${COREOS_VERSION}/$kernel"} \
    ${kernel:+"${DOWNLOAD}/signed/boards/${BOARD}/${COREOS_VERSION}/$kernel.sig"} \
    ${grub:+"${DOWNLOAD}/signed/boards/${BOARD}/${COREOS_VERSION}/$grub"} \
    ${grub:+"${DOWNLOAD}/signed/boards/${BOARD}/${COREOS_VERSION}/$grub.sig"} \
    ${shim:+"${DOWNLOAD}/signed/boards/${BOARD}/${COREOS_VERSION}/$shim"} \
    ${shim:+"${DOWNLOAD}/signed/boards/${BOARD}/${COREOS_VERSION}/$shim.sig"} \
    /mnt/host/source/src
[[ -n "$kernel" ]] && gpg --verify "src/$kernel.sig"
[[ -n "$grub" ]] && gpg --verify "src/$grub.sig"
[[ -n "$shim" ]] && gpg --verify "src/$shim.sig"

script image_inject_bootchain --board=${BOARD} \
                              --group=stable \
                              --from=/mnt/host/source/src \
                              --output_root=/mnt/host/source/tmp \
                              ${grub:+--efi_grub_path=/mnt/host/source/src/$grub} \
                              ${kernel:+--kernel_path=/mnt/host/source/src/$kernel} \
                              ${shim:+--shim_path=/mnt/host/source/src/$shim} \
                              --replace \
                              --sign=buildbot@coreos.com \
                              --sign_digests=buildbot@coreos.com \
                              --upload_root=${UPLOAD}/stable \
                              --upload

script image_set_group --board=${BOARD} \
                       --group=alpha \
                       --from=/mnt/home/source/tmp/${BOARD}/stable-latest \
                       --output_root=/mnt/host/source/tmp \
                       --sign=buildbot@coreos.com \
                       --sign_digests=buildbot@coreos.com \
                       --upload_root=${UPLOAD}/alpha \
                       --upload

script image_set_group --board=${BOARD} \
                       --group=beta \
                       --from=/mnt/home/source/tmp/${BOARD}/stable-latest \
                       --output_root=/mnt/host/source/tmp \
                       --sign=buildbot@coreos.com \
                       --sign_digests=buildbot@coreos.com \
                       --upload_root=${UPLOAD}/beta \
                       --upload
'''  /* Editor quote safety: ' */
        }
    }
}

stage('Downstream') {
    build job: 'vm-matrix', parameters: [
        string(name: 'BOARD', value: params.BOARD),
        string(name: 'COREOS_OFFICIAL', '1'),
        string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
        string(name: 'MANIFEST_REF', value: params.MANIFEST_REF),
        string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
        string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
    ]
}
