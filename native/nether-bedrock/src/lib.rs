use bedrock_cracker::raw_data::block::Block;
use bedrock_cracker::raw_data::block_type::BlockType;
use bedrock_cracker::raw_data::modes::{BedrockGeneration, OutputMode};
use bedrock_cracker::raw_data::sender::Sender;
use bedrock_cracker::{search_bedrock_pattern, CrackProgress};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{channel, Sender as ChannelSender};

static CANCELLED: AtomicBool = AtomicBool::new(false);
static PROGRESS: AtomicU64 = AtomicU64::new(0);

#[derive(Clone)]
struct ProgressSender {
    channel: ChannelSender<CrackProgress>,
}

impl Sender for ProgressSender {
    fn cancelled(&self) -> bool { CANCELLED.load(Ordering::Relaxed) }

    fn send(&self, event: CrackProgress) -> bool {
        if CANCELLED.load(Ordering::Relaxed) {
            return false;
        }
        if let CrackProgress::Progress(amount) = event {
            PROGRESS.fetch_add(amount, Ordering::Relaxed);
            self.channel.send(CrackProgress::Progress(amount)).is_ok()
        } else {
            self.channel.send(event).is_ok()
        }
    }
}

#[no_mangle]
pub extern "C" fn seedy_nether_cancel() {
    CANCELLED.store(true, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn seedy_nether_reset() {
    CANCELLED.store(false, Ordering::Relaxed);
    PROGRESS.store(0, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn seedy_nether_progress() -> u64 {
    PROGRESS.load(Ordering::Relaxed)
}

#[no_mangle]
pub unsafe extern "C" fn seedy_nether_crack(
    input: *const i32,
    count: usize,
    threads: u64,
    expected_hash: u64,
    output: *mut i64,
) -> i64 {
    if input.is_null() || output.is_null() || count == 0 || count > 8192 {
        return -1;
    }
    if CANCELLED.load(Ordering::Relaxed) { return -4; }
    let coordinates = std::slice::from_raw_parts(input, count * 4);
    let mut floor = 0;
    let mut roof = 0;
    let mut blocks = Vec::with_capacity(count);
    for entry in coordinates.chunks_exact(4) {
        let y = entry[1];
        if y == 4 {
            floor += 1;
        } else if y == 123 {
            roof += 1;
        } else {
            return -2;
        }
        let kind = match entry[3] {
            0 => BlockType::BEDROCK,
            1 => BlockType::OTHER,
            _ => return -2,
        };
        blocks.push(Block::new(entry[0], y, entry[2], kind));
    }
    if floor == 0 || roof == 0 {
        return -2;
    }
    if CANCELLED.load(Ordering::Relaxed) { return -4; }
    let (channel, receiver) = channel();
    search_bedrock_pattern(
        &blocks,
        threads.clamp(1, 64),
        BedrockGeneration::Normal,
        OutputMode::StructureSeed,
        ProgressSender { channel },
    );
    let mut found = HashSet::new();
    let mut world_seed = None;
    let mut overflow = false;
    while let Ok(event) = receiver.recv() {
        if CANCELLED.load(Ordering::Relaxed) {
            continue;
        }
        if let CrackProgress::Seed(seed) = event {
            if found.insert(seed) {
                if found.len() > 4096 {
                    CANCELLED.store(true, Ordering::Relaxed);
                    overflow = true;
                    continue;
                }
                let lower = seed & ((1_u64 << 48) - 1);
                for upper in 0..65536_u64 {
                    if upper & 255 == 0 && CANCELLED.load(Ordering::Relaxed) { break; }
                    let candidate = (upper << 48) | lower;
                    let digest = Sha256::digest(candidate.to_le_bytes());
                    let candidate_hash = u64::from_le_bytes(digest[..8].try_into().unwrap());
                    if candidate_hash == expected_hash {
                        world_seed = Some(candidate);
                        CANCELLED.store(true, Ordering::Relaxed);
                        break;
                    }
                }
            }
        }
    }
    if let Some(seed) = world_seed {
        *output = seed as i64;
        1
    } else if overflow {
        -3
    } else if CANCELLED.load(Ordering::Relaxed) {
        -4
    } else {
        0
    }
}
