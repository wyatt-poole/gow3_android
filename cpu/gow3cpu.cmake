# libgow3cpu.so: guest_cpu.h on FEXCore, linked statically against FEX's libraries.
set(GOW3CPU_DIR "${CMAKE_CURRENT_LIST_DIR}")
add_library(gow3cpu SHARED "${GOW3CPU_DIR}/guest_cpu_fex.cpp" "${GOW3CPU_DIR}/host_call_arm64.S")
target_include_directories(gow3cpu PRIVATE "${GOW3CPU_DIR}/../src" "${CMAKE_SOURCE_DIR}/Source" "${CMAKE_BINARY_DIR}/generated")
target_link_libraries(gow3cpu PRIVATE FEXCore Common JemallocLibs fmt::fmt)
target_compile_options(gow3cpu PRIVATE ${FEX_TUNE_COMPILE_FLAGS})
set_target_properties(gow3cpu PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
  C_VISIBILITY_PRESET hidden
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN TRUE)
