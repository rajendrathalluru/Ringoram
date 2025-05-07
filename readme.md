# Ring ORAM: Recursive & XOR-Optimized Implementation

> ✅ This version includes encryption in the XOR-based path access optimization.

---

## 📦 Features

- ✅ Implements Ring ORAM with:
  - Recursive position map
  - XOR-based online bandwidth reduction (with encryption)
  - Reverse lexicographic eviction
  - Early reshuffling of buckets
- ✅ Configurable parameters (tree depth, Z, recursion depth)
- ✅ Modular Java codebase with client-server architecture
- ✅ Server deployable on Amazon EC2 or local machine
- ✅ Functional testing with detailed log output (stash, position map)

---

## ⚙️ Requirements

- Java 11+  
- [`guava-19.0.jar`](https://mvnrepository.com/artifact/com.google.guava/guava/19.0)  
  (Used for in-memory caching and collections)

---

## 📁 Project Structure

```
RingORAM/
├── src/
│   ├── com/
│   │   ├── server/                # Server-side bucket tree handler
│   │   ├── client/                # Client entry point and logic
│   │   ├── ringoram/              # Core Ring ORAM logic
│   │   └── messages/              # Serializable request/response classes
├── config/
│   └── oram.properties            # Z, depth, recursion, XOR flags
├── libs/
│   └── guava-19.0.jar             # External library
└── README.md
```

---

## 🏁 Getting Started

### 🔧 Configuration
Edit `oram.properties` to set ORAM parameters:
```properties
TREE_DEPTH=10
BUCKET_SIZE=4
SERVER_IP=your.ec2.ip.address
SERVER_PORT=9000
```

### 🚀 Running the System

1. **Start the Server**  
   From `com.server` package:
   ```bash
   java -cp bin:libs/guava-19.0.jar com.server.Server
   ```

2. **Run the Client**  
   From `com.client` package:
   ```bash
   java -cp bin:libs/guava-19.0.jar com.client.Client
   ```

⚠️ Ensure the server is running before the client starts.

---

## 📊 Testing and Debugging

- Logs stash size after every access.
- Dumps eviction path and bucket contents.
- Recursive client checks position map correctness across layers.
- XOR compression is tested with encryption using symmetric cryptography.

---

## 🧱 Internal Design Notes

### Bucket Metadata (Used in `com.ringoram`)

Ring ORAM separates real and dummy block tracking using metadata structures:

- **`meta_buf` in `BucketMetadata`:**
  - Indices `0` to `Configs.REAL_BLOCK_COUNT - 1`: real block indices
  - Indices `Configs.REAL_BLOCK_COUNT` to `(REAL_BLOCK_COUNT + Z - 1)`: data offsets in `bucket_data`

- **`offset` array in `BucketMetadata`:**
  - First `REAL_BLOCK_COUNT` entries: offsets for real blocks
  - Remaining: dummy block offsets

This layout allows the client to selectively fetch blocks during path reads and helps implement reshuffling policies securely.

---

## 📚 References

- Ren, Ling, et al. "**Constants Count: Practical Improvements to Oblivious RAM**", USENIX Security Symposium 2015.  
  [https://www.usenix.org/conference/usenixsecurity15/technical-sessions/presentation/ren-ling](https://www.usenix.org/conference/usenixsecurity15/technical-sessions/presentation/ren-ling)

---

## 🧑‍💻 Authors

- Rajendra Thalluru  
- Sravan Nekkanti  
- Ritish Reddy  
