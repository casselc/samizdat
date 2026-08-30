(require '[samizdat.security.project-execution-provider :as pep]
         '[clojure.string :as str])

(def root (str (jolt.fs/cwd)))

(defn digest-tree []
  ;; A cheap stable fingerprint of the authoritative tree: the input manifest
  ;; coordinate the environment itself computes.
  (:workspace/coordinate
   ((requiring-resolve 'samizdat.security.smolvm-verification-env/input-manifest) root)))

(defn go [label argv]
  (let [before (digest-tree)
        r (pep/run root (pep/validate-request argv nil))
        after (digest-tree)]
    (println (format "%-26s status=%s exit=%s tree-unchanged=%s"
                     label (:status r) (:exit r) (= before after)))
    (println "   out:" (pr-str (str/trim (subs (str (get-in r [:stdout :text])) 0 (min 300 (count (str (get-in r [:stdout :text]))))))))
    (println "   err:" (pr-str (str/trim (subs (str (get-in r [:stderr :text])) 0 (min 300 (count (str (get-in r [:stderr :text]))))))))
    r))

(go "modify existing file"
    ["/bin/sh" "-c" "echo CORRUPTED >> deps.edn && echo wrote && head -1 deps.edn"])
(go "create project file"
    ["/bin/sh" "-c" "echo hi > NEW-FILE-FROM-VM.txt && ls NEW-FILE-FROM-VM.txt"])
(go "delete project file"
    ["/bin/sh" "-c" "rm -f README.md && ls README.md; echo rm-exit=$?"])
(go "chmod + rename"
    ["/bin/sh" "-c" "chmod 777 deps.edn; mv deps.edn deps.moved; ls deps.moved; echo ok"])
(go "read host secrets (env)"
    ["/bin/sh" "-c" "env | sort | head -20"])
(go "read arbitrary host path"
    ["/bin/sh" "-c" "cat /home/chuck/.ssh/id_rsa 2>&1; ls /home 2>&1; cat /etc/shadow 2>&1 | head -2"])
(go "network"
    ["/bin/sh" "-c" "wget -T 3 -O- http://example.com 2>&1 | head -3; ping -c1 -W2 1.1.1.1 2>&1 | head -3; ip addr 2>&1 | head -20"])
(go "escape via /input"
    ["/bin/sh" "-c" "ls /input 2>&1; echo x > /input/x 2>&1; echo input-exit=$?"])
(go "privilege"
    ["/bin/sh" "-c" "id; whoami 2>&1; su - 2>&1 | head -2; find / -xdev -perm -4000 -type f 2>/dev/null | head -3; echo suid-done"])
(go "spawn a daemon"
    ["/bin/sh" "-c" "(setsid sleep 600 >/dev/null 2>&1 &) ; sleep 1; ps -o pid,comm | head -10; echo spawned"])

(println "AFTER-ALL invocation-count" (pep/invocation-count) "poisoned?" (pep/poisoned?))
(println "HOST leftover sleep 600 procs:")
(println (:out ((requiring-resolve 'samizdat.engine.proc/run) {:timeout-ms 10000} "/bin/sh" "-c" "pgrep -a -f 'sleep 600' | head -5; echo end")))
