#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdint>
#include <fcntl.h>
#include <iterator>
#include <limits>
#include <mutex>
#include <string>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

#include "napi/native_api.h"

namespace {

constexpr const char *kAuraExecutable = "/data/app/bin/aura-launcher";
constexpr const char *kLogPathPrefix = "/data/storage/";
constexpr const char *kLogPathSuffix = "/files/aura-launcher.log";
constexpr size_t kMaximumPathBytes = 4096;
constexpr size_t kMaximumMessageBytes = 4096;
constexpr size_t kMaximumDiagnosticBytes = 16 * 1024;
constexpr std::chrono::seconds kStartupWindow(15);

enum class LaunchCode : int32_t {
    Ok = 0,
    InvalidArgument = 10,
    AlreadyRunning = 11,
    ExecutableMissing = 12,
    LogOpenFailed = 13,
    ForkFailed = 14,
};

struct ProcessState {
    pid_t livePid = -1;
    pid_t lastPid = -1;
    int32_t lastExitCode = -1;
    std::string logPath;
    std::chrono::steady_clock::time_point startedAt{};
};

std::mutex gProcessMutex;
ProcessState gProcessState;

std::string BoundString(std::string value, size_t maximumBytes)
{
    if (value.size() > maximumBytes) {
        value.resize(maximumBytes);
    }
    return value;
}

napi_value CreateString(napi_env env, const std::string &value, size_t maximumBytes = kMaximumMessageBytes)
{
    const std::string bounded = BoundString(value, maximumBytes);
    napi_value result = nullptr;
    if (napi_create_string_utf8(env, bounded.data(), bounded.size(), &result) != napi_ok) {
        return nullptr;
    }
    return result;
}

napi_value CreateInt32(napi_env env, int32_t value)
{
    napi_value result = nullptr;
    return napi_create_int32(env, value, &result) == napi_ok ? result : nullptr;
}

napi_value CreateInt64(napi_env env, int64_t value)
{
    napi_value result = nullptr;
    return napi_create_int64(env, value, &result) == napi_ok ? result : nullptr;
}

napi_value CreateBoolean(napi_env env, bool value)
{
    napi_value result = nullptr;
    return napi_get_boolean(env, value, &result) == napi_ok ? result : nullptr;
}

bool SetNamedProperty(napi_env env, napi_value object, const char *name, napi_value value)
{
    return value != nullptr && napi_set_named_property(env, object, name, value) == napi_ok;
}

napi_value CreateLaunchResult(napi_env env, LaunchCode code, pid_t pid, const std::string &message)
{
    napi_value result = nullptr;
    if (napi_create_object(env, &result) != napi_ok) {
        return nullptr;
    }
    if (!SetNamedProperty(env, result, "code", CreateInt32(env, static_cast<int32_t>(code))) ||
        !SetNamedProperty(env, result, "pid", CreateInt64(env, static_cast<int64_t>(pid))) ||
        !SetNamedProperty(env, result, "message", CreateString(env, message))) {
        return nullptr;
    }
    return result;
}

napi_value CreatePollResult(napi_env env, bool running, bool exited, int32_t exitCode,
                            const std::string &message)
{
    napi_value result = nullptr;
    if (napi_create_object(env, &result) != napi_ok) {
        return nullptr;
    }
    if (!SetNamedProperty(env, result, "running", CreateBoolean(env, running)) ||
        !SetNamedProperty(env, result, "exited", CreateBoolean(env, exited)) ||
        !SetNamedProperty(env, result, "exitCode", CreateInt32(env, exitCode)) ||
        !SetNamedProperty(env, result, "message", CreateString(env, message))) {
        return nullptr;
    }
    return result;
}

bool ReadSingleArgument(napi_env env, napi_callback_info info, napi_value &argument, std::string &error)
{
    size_t argumentCount = 0;
    if (napi_get_cb_info(env, info, &argumentCount, nullptr, nullptr, nullptr) != napi_ok || argumentCount != 1) {
        error = "Exactly one argument is required.";
        return false;
    }

    size_t capacity = 1;
    if (napi_get_cb_info(env, info, &capacity, &argument, nullptr, nullptr) != napi_ok || capacity != 1) {
        error = "Unable to read the required argument.";
        return false;
    }
    return true;
}

bool ReadStringArgument(napi_env env, napi_callback_info info, std::string &value, std::string &error)
{
    napi_value argument = nullptr;
    if (!ReadSingleArgument(env, info, argument, error)) {
        return false;
    }
    napi_valuetype type = napi_undefined;
    if (napi_typeof(env, argument, &type) != napi_ok || type != napi_string) {
        error = "The argument must be a string.";
        return false;
    }

    size_t byteLength = 0;
    if (napi_get_value_string_utf8(env, argument, nullptr, 0, &byteLength) != napi_ok ||
        byteLength == 0 || byteLength > kMaximumPathBytes) {
        error = "The path must contain between 1 and 4096 UTF-8 bytes.";
        return false;
    }
    std::vector<char> buffer(byteLength + 1, '\0');
    size_t actualLength = 0;
    if (napi_get_value_string_utf8(env, argument, buffer.data(), buffer.size(), &actualLength) != napi_ok ||
        actualLength != byteLength) {
        error = "The path is not a stable UTF-8 string.";
        return false;
    }
    value.assign(buffer.data(), actualLength);
    return true;
}

bool ReadPidArgument(napi_env env, napi_callback_info info, pid_t &pid, std::string &error)
{
    napi_value argument = nullptr;
    if (!ReadSingleArgument(env, info, argument, error)) {
        return false;
    }
    napi_valuetype type = napi_undefined;
    double value = 0;
    if (napi_typeof(env, argument, &type) != napi_ok || type != napi_number ||
        napi_get_value_double(env, argument, &value) != napi_ok || !std::isfinite(value) ||
        std::trunc(value) != value || value <= 0 ||
        value > std::numeric_limits<pid_t>::max()) {
        error = "The PID must be a positive process identifier.";
        return false;
    }
    pid = static_cast<pid_t>(value);
    return true;
}

bool IsValidLogPath(const std::string &path)
{
    if (path.empty() || path.size() > kMaximumPathBytes || path.front() != '/' ||
        path.find('\0') != std::string::npos ||
        path.rfind(kLogPathPrefix, 0) != 0 || path.size() <= std::char_traits<char>::length(kLogPathSuffix)) {
        return false;
    }
    const size_t suffixOffset = path.size() - std::char_traits<char>::length(kLogPathSuffix);
    if (path.compare(suffixOffset, std::string::npos, kLogPathSuffix) != 0) {
        return false;
    }
    return path.find("//") == std::string::npos && path.find("/./") == std::string::npos &&
        path.find("/../") == std::string::npos;
}

int32_t DecodeExitCode(int status)
{
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

void RefreshProcessStateLocked()
{
    if (gProcessState.livePid <= 0) {
        return;
    }
    int status = 0;
    const pid_t result = waitpid(gProcessState.livePid, &status, WNOHANG);
    if (result == gProcessState.livePid) {
        gProcessState.lastPid = gProcessState.livePid;
        gProcessState.lastExitCode = DecodeExitCode(status);
        gProcessState.livePid = -1;
    } else if (result < 0 && errno == ECHILD) {
        gProcessState.lastPid = gProcessState.livePid;
        gProcessState.lastExitCode = -1;
        gProcessState.livePid = -1;
    }
}

int OpenPrivateLog(const std::string &path)
{
    const int descriptor = open(path.c_str(), O_CREAT | O_WRONLY | O_APPEND | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (descriptor < 0) {
        return -1;
    }
    if (fchmod(descriptor, 0600) != 0 || ftruncate(descriptor, 0) != 0) {
        close(descriptor);
        return -1;
    }
    return descriptor;
}

napi_value StartAura(napi_env env, napi_callback_info info)
{
    std::string logPath;
    std::string error;
    if (!ReadStringArgument(env, info, logPath, error) || !IsValidLogPath(logPath)) {
        return CreateLaunchResult(env, LaunchCode::InvalidArgument, -1,
                                  error.empty() ? "Invalid application-private log path." : error);
    }
    if (access(kAuraExecutable, X_OK) != 0) {
        return CreateLaunchResult(env, LaunchCode::ExecutableMissing, -1,
                                  "The private Aura Launcher HNP executable is unavailable.");
    }

    std::lock_guard<std::mutex> lock(gProcessMutex);
    RefreshProcessStateLocked();
    if (gProcessState.livePid > 0) {
        return CreateLaunchResult(env, LaunchCode::AlreadyRunning, gProcessState.livePid,
                                  "Aura Launcher is already running.");
    }

    const int logDescriptor = OpenPrivateLog(logPath);
    if (logDescriptor < 0) {
        return CreateLaunchResult(env, LaunchCode::LogOpenFailed, -1,
                                  "Unable to open the application-private diagnostic log.");
    }

    const pid_t child = fork();
    if (child < 0) {
        close(logDescriptor);
        return CreateLaunchResult(env, LaunchCode::ForkFailed, -1, "Unable to create the Aura Launcher process.");
    }
    if (child == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() == 1 ||
            dup2(logDescriptor, STDOUT_FILENO) < 0 || dup2(logDescriptor, STDERR_FILENO) < 0) {
            close(logDescriptor);
            _exit(126);
        }
        close(logDescriptor);
        char *const argv[] = {const_cast<char *>(kAuraExecutable), nullptr};
        execv(kAuraExecutable, argv);
        constexpr char failure[] = "Unable to execute the private Aura Launcher HNP.\n";
        static_cast<void>(write(STDERR_FILENO, failure, sizeof(failure) - 1));
        _exit(127);
    }

    close(logDescriptor);
    gProcessState.livePid = child;
    gProcessState.lastPid = -1;
    gProcessState.lastExitCode = -1;
    gProcessState.logPath = logPath;
    gProcessState.startedAt = std::chrono::steady_clock::now();
    return CreateLaunchResult(env, LaunchCode::Ok, child, "Aura Launcher process created.");
}

napi_value PollAura(napi_env env, napi_callback_info info)
{
    pid_t requestedPid = -1;
    std::string error;
    if (!ReadPidArgument(env, info, requestedPid, error)) {
        return CreatePollResult(env, false, false, -1, error);
    }

    std::lock_guard<std::mutex> lock(gProcessMutex);
    RefreshProcessStateLocked();
    if (requestedPid == gProcessState.livePid) {
        const bool passedStartupWindow =
            std::chrono::steady_clock::now() - gProcessState.startedAt >= kStartupWindow;
        return CreatePollResult(env, true, false, -1,
                                passedStartupWindow ? "Process started; UI readiness is unverified." :
                                                      "Process is starting.");
    }
    if (requestedPid == gProcessState.lastPid) {
        return CreatePollResult(env, false, true, gProcessState.lastExitCode, "Process exited.");
    }
    return CreatePollResult(env, false, false, -1, "The PID is not owned by Aura Launcher.");
}

std::string ReadTail(const std::string &path)
{
    const int descriptor = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        return {};
    }

    struct stat metadata {};
    if (fstat(descriptor, &metadata) != 0 || !S_ISREG(metadata.st_mode)) {
        close(descriptor);
        return "The diagnostic log is not a regular file.";
    }
    const off_t offset = metadata.st_size > static_cast<off_t>(kMaximumDiagnosticBytes)
        ? metadata.st_size - static_cast<off_t>(kMaximumDiagnosticBytes)
        : 0;
    if (lseek(descriptor, offset, SEEK_SET) < 0) {
        close(descriptor);
        return "Unable to seek in the diagnostic log.";
    }

    std::array<char, kMaximumDiagnosticBytes> buffer{};
    size_t total = 0;
    while (total < buffer.size()) {
        const ssize_t count = read(descriptor, buffer.data() + total, buffer.size() - total);
        if (count > 0) {
            total += static_cast<size_t>(count);
        } else if (count == 0) {
            break;
        } else if (errno != EINTR) {
            close(descriptor);
            return "Unable to read the diagnostic log.";
        }
    }
    close(descriptor);
    return std::string(buffer.data(), total);
}

napi_value ReadDiagnosticTail(napi_env env, napi_callback_info info)
{
    std::string logPath;
    std::string error;
    if (!ReadStringArgument(env, info, logPath, error) || !IsValidLogPath(logPath)) {
        return CreateString(env, error.empty() ? "Invalid application-private log path." : error,
                            kMaximumDiagnosticBytes);
    }
    {
        std::lock_guard<std::mutex> lock(gProcessMutex);
        if (gProcessState.logPath.empty() || logPath != gProcessState.logPath) {
            return CreateString(env, "The diagnostic path is not owned by the Aura process.",
                                kMaximumDiagnosticBytes);
        }
    }
    return CreateString(env, ReadTail(logPath), kMaximumDiagnosticBytes);
}

napi_value Init(napi_env env, napi_value exports)
{
    const napi_property_descriptor descriptors[] = {
        {"startAura", nullptr, StartAura, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"pollAura", nullptr, PollAura, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"readDiagnosticTail", nullptr, ReadDiagnosticTail, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    if (napi_define_properties(env, exports, std::size(descriptors), descriptors) != napi_ok) {
        return nullptr;
    }
    return exports;
}

napi_module gAuraLauncherModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "aura_launcher",
    .nm_priv = nullptr,
    .reserved = {0},
};

} // namespace

extern "C" __attribute__((constructor)) void RegisterAuraLauncherModule()
{
    napi_module_register(&gAuraLauncherModule);
}
