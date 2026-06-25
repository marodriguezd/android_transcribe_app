use jni::objects::JObject;
use jni::JNIEnv;

pub struct MemoryMappedAsset {
    mapped_ptr: *mut libc::c_void,
    mapped_length: usize,
    slice_offset: usize,
    slice_len: usize,
}

impl MemoryMappedAsset {
    pub fn as_slice(&self) -> &[u8] {
        unsafe {
            std::slice::from_raw_parts(
                (self.mapped_ptr as *const u8).add(self.slice_offset),
                self.slice_len,
            )
        }
    }
}

unsafe impl Send for MemoryMappedAsset {}
unsafe impl Sync for MemoryMappedAsset {}

impl Drop for MemoryMappedAsset {
    fn drop(&mut self) {
        unsafe {
            libc::munmap(self.mapped_ptr, self.mapped_length);
        }
    }
}

pub fn mmap_asset(
    env: &mut JNIEnv,
    asset_manager: &JObject,
    asset_path: &str,
) -> anyhow::Result<MemoryMappedAsset> {
    let path_jstring = env.new_string(asset_path)?;
    let asset_fd_obj = env
        .call_method(
            asset_manager,
            "openFd",
            "(Ljava/lang/String;)Landroid/content/res/AssetFileDescriptor;",
            &[(&path_jstring).into()],
        )?
        .l()?;

    let start_offset = env
        .call_method(&asset_fd_obj, "getStartOffset", "()J", &[])?
        .j()?;
    let length = env
        .call_method(&asset_fd_obj, "getLength", "()J", &[])?
        .j()?;

    let pfd_obj = env
        .call_method(
            &asset_fd_obj,
            "getParcelFileDescriptor",
            "()Landroid/os/ParcelFileDescriptor;",
            &[],
        )?
        .l()?;

    let raw_fd = env
        .call_method(&pfd_obj, "getFd", "()I", &[])?
        .i()?;

    // Duplicate the file descriptor
    let dup_fd = unsafe { libc::dup(raw_fd) };
    if dup_fd < 0 {
        // Make sure we close Java side asset_fd before returning
        let _ = env.call_method(&asset_fd_obj, "close", "()V", &[]);
        return Err(anyhow::anyhow!("dup failed: {}", std::io::Error::last_os_error()));
    }

    // Now close the Java-side asset fd
    let _ = env.call_method(&asset_fd_obj, "close", "()V", &[]);

    // Get page size
    let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) } as usize;
    let offset = start_offset as usize;
    let aligned_offset = (offset / page_size) * page_size;
    let alignment_difference = offset - aligned_offset;
    let mapped_length = (length as usize) + alignment_difference;

    let mapped_ptr = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            mapped_length,
            libc::PROT_READ,
            libc::MAP_SHARED,
            dup_fd,
            aligned_offset as libc::off_t,
        )
    };

    // We can close the dup_fd immediately after mmap call
    unsafe {
        libc::close(dup_fd);
    }

    if mapped_ptr == libc::MAP_FAILED {
        return Err(anyhow::anyhow!(
            "mmap failed: {}",
            std::io::Error::last_os_error()
        ));
    }

    Ok(MemoryMappedAsset {
        mapped_ptr,
        mapped_length,
        slice_offset: alignment_difference,
        slice_len: length as usize,
    })
}

pub fn read_asset_to_string(
    env: &mut JNIEnv,
    asset_manager: &JObject,
    asset_path: &str,
) -> anyhow::Result<String> {
    let path_jstring = env.new_string(asset_path)?;
    let stream_val = env.call_method(
        asset_manager,
        "open",
        "(Ljava/lang/String;)Ljava/io/InputStream;",
        &[(&path_jstring).into()],
    )?;
    let stream_obj = stream_val.l()?;

    let mut content = Vec::new();
    let mut buffer = [0u8; 8192];
    let buffer_j = env.new_byte_array(8192)?;

    loop {
        let bytes_read = env
            .call_method(&stream_obj, "read", "([B)I", &[(&buffer_j).into()])?
            .i()?;

        if bytes_read == -1 {
            break;
        }

        let bytes_read_usize = bytes_read as usize;
        let buffer_slice = unsafe {
            std::slice::from_raw_parts_mut(buffer.as_mut_ptr() as *mut i8, bytes_read_usize)
        };

        env.get_byte_array_region(&buffer_j, 0, buffer_slice)?;
        content.extend_from_slice(&buffer[0..bytes_read_usize]);
    }

    env.call_method(&stream_obj, "close", "()V", &[])?;

    let string_content = String::from_utf8(content)?;
    Ok(string_content)
}
