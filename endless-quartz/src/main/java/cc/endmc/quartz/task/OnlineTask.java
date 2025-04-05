package cc.endmc.quartz.task;

import cc.endmc.common.core.redis.RedisCache;
import cc.endmc.common.utils.StringUtils;
import cc.endmc.common.utils.http.HttpUtils;
import cc.endmc.server.common.MapCache;
import cc.endmc.server.common.constant.CacheKey;
import cc.endmc.server.common.rconclient.RconClient;
import cc.endmc.server.common.service.RconService;
import cc.endmc.server.domain.permission.WhitelistInfo;
import cc.endmc.server.domain.player.PlayerDetails;
import cc.endmc.server.enums.Identity;
import cc.endmc.server.mapper.permission.WhitelistInfoMapper;
import cc.endmc.server.mapper.player.PlayerDetailsMapper;
import cc.endmc.server.service.player.IPlayerDetailsService;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ClassName: OnlineTask <br>
 * Description:
 * date: 2024/4/7 下午7:51 <br>
 *
 * @author Administrator <br>
 * @since JDK 1.8
 */
@Slf4j
@Component("onlineTask")
public class OnlineTask {

    @Autowired
    private WhitelistInfoMapper whitelistInfoMapper;
    @Autowired
    private PlayerDetailsMapper playerDetailsMapper;
    @Autowired
    private IPlayerDetailsService playerDetailsService;
    @Autowired
    private RedisCache cache;
    @Autowired
    private RconService rconService;

    /**
     * 根据用户uuid同步用户名称
     * Api：<a href="https://sessionserver.mojang.com/session/minecraft/profile/">...</a>{uuid}
     */
    public void syncUserNameForUuid() {
        log.debug("syncUserNameForUuid start");
        ArrayList<String> list = new ArrayList<>();
        // 查询所有正版用户
        WhitelistInfo whitelistInfo = new WhitelistInfo();
        whitelistInfo.setOnlineFlag(1L);
        whitelistInfoMapper.selectWhitelistInfoList(whitelistInfo).forEach(whitelist -> {
            // 查询用户名称
            try {
                String json = HttpUtils.sendGet("https://sessionserver.mojang.com/session/minecraft/profile/" + whitelist.getUserUuid().replace("-", ""));
                if (StringUtils.isNotEmpty(json)) {
                    final String oldName = whitelist.getUserName();
                    // json实例化
                    JSONObject jsonObject = JSONObject.parseObject(json);
                    String newName = jsonObject.getString("name");
                    if (newName.equals(whitelist.getUserName())) {
                        return;
                    }
                    // 更新用户名称
                    whitelist.setUserName(newName);
                    list.add(whitelist.getUserName());
                    whitelistInfoMapper.updateWhitelistInfo(whitelist);

                    // 更新玩家详情
                    final PlayerDetails details = new PlayerDetails();
                    details.setWhitelistId(whitelist.getId());
                    final List<PlayerDetails> playerDetails = playerDetailsService.selectPlayerDetailsList(details);

                    if (!playerDetails.isEmpty()) {
                        final PlayerDetails player = playerDetails.get(0);
                        player.setUserName(newName);
                        player.setUpdateTime(new Date());

                        JSONObject data = new JSONObject();
                        if (player.getParameters() != null) {
                            data = JSONObject.parseObject(player.getParameters());
                            data.getJSONArray("name_history").add(oldName);
                        } else {
                            data.put("name_history", new ArrayList<String>() {{
                                add(oldName);
                            }});
                            player.setParameters(data.toJSONString());
                        }
                        playerDetailsMapper.updatePlayerDetails(player);
                    } else {
                        PlayerDetails player = new PlayerDetails();
                        player.setWhitelistId(whitelist.getId());
                        player.setCreateTime(new Date());
                        player.setQq(whitelist.getQqNum());
                        player.setIdentity(Identity.PLAYER.getValue());
                        player.setUserName(newName);
                        player.setParameters("{}");
                        playerDetailsMapper.insertPlayerDetails(player);
                    }

                }
            } catch (Exception e) {
                log.error("syncUserNameForUuid error", e);
            }
        });
        log.debug("syncUserNameForUuid list: {}", list);
        log.debug("syncUserNameForUuid end");
    }

    /**
     * 根据高密度定时查询在线用户更新最后一次上线时间
     */
    public void monitor() {
        Map<String, RconClient> map = MapCache.getMap();
        if (map == null || map.isEmpty()) {
            return;
        }
        Set<String> onlinePlayer = new HashSet<>();

        for (Map.Entry<String, RconClient> entry : map.entrySet()) {
            String serverName = entry.getKey();
            RconClient rconClient = entry.getValue();
            int retryCount = 0;
            int maxRetries = 3;  // 最大重试次数

            while (retryCount < maxRetries) {
                try {
                    // 先发送一个简单的命令测试连接
                    String testResponse = rconClient.sendCommand("whitelist");
                    if (testResponse == null) {
                        throw new Exception("Connection test failed");
                    }

                    // 获取在线玩家列表
                    String list = rconClient.sendCommand("list");
                    if (list == null || (!list.startsWith("There are"))) {
                        list = rconClient.sendCommand("minecraft:list");
                    }
                    // 新增Velocity代理端支持
                    if (list == null || (!list.startsWith("There are") && !list.startsWith("Online ("))) {
                        list = rconClient.sendCommand("glist all");
                    }

                    if (StringUtils.isNotEmpty(list)) {
                        if (list.contains("There are")) {
                            String[] parts = list.split(":");
                            if (parts.length > 1) {
                                String playerList = parts[1].trim();
                                if (!playerList.isEmpty()) {
                                    String[] players = playerList.split(", ");
                                    for (String player : players) {
                                        onlinePlayer.add(player.toLowerCase().trim());
                                    }
                                }
                            }
                        } else if (list.contains("Online (")) {
                            String[] parts = list.split(":");
                            if (parts.length > 1) {
                                String playerList = parts[1].trim();
                                if (!playerList.isEmpty()) {
                                    String[] players = playerList.split(", ");
                                    for (String player : players) {
                                        onlinePlayer.add(player.toLowerCase().trim());
                                    }
                                }
                            }
                        } else {
                            // 处理Velocity的glist all响应
                            String[] lines = list.split("\n");
                            for (String line : lines) {
                                // 跳过总人数统计行
                                if (line.contains("§e共有") || line.contains("已连接至此代理服务器")) {
                                    continue;
                                }
                                // 去除颜色代码
                                String cleanedLine = line.replaceAll("§[0-9a-fk-or]", "");
                                if (cleanedLine.contains(":")) {
                                    String[] parts = cleanedLine.split(":");
                                    if (parts.length > 1) {
                                        String playersStr = parts[1].trim();
                                        if (!playersStr.isEmpty()) {
                                            String[] players = playersStr.split(", ");
                                            for (String player : players) {
                                                onlinePlayer.add(player.toLowerCase().trim());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 成功获取数据，跳出重试循环
                    break;

                } catch (Exception e) {
                    retryCount++;
                    if (retryCount >= maxRetries) {
                        log.error("Failed to get online players from server {} after {} retries: {}",
                                serverName, maxRetries, e.getMessage());
                        // 尝试重新建立连接
                        try {
                            rconClient.close();
                            // 重新初始化Rcon连接
                            rconService.reconnect(serverName);
                        } catch (Exception closeEx) {
                            log.error("Failed to close RCON connection for server {}: {}",
                                    serverName, closeEx.getMessage());
                        }
                    } else {
                        log.warn("Retry {} for server {}: {}",
                                retryCount, serverName, e.getMessage());
                        try {
                            // 重试前等待一小段时间
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        if (cache == null) {
            log.error("Cache is not initialized.");
            return;
        }

        // 获取缓存中的在线玩家
        Set<String> cacheOnlinePlayer = cache.getCacheObject(CacheKey.ONLINE_PLAYER_KEY);
        if (cacheOnlinePlayer == null) {
            cacheOnlinePlayer = new HashSet<>();
        }

        // 找出新上线的玩家（当前在线但缓存中没有的）
        Set<String> newOnlinePlayers = new HashSet<>(onlinePlayer);
        newOnlinePlayers.removeAll(cacheOnlinePlayer);

        // 找出新下线的玩家（缓存中有但当前不在线的）
        Set<String> newOfflinePlayers = new HashSet<>(cacheOnlinePlayer);
        newOfflinePlayers.removeAll(onlinePlayer);

        // 更新上线时间
        if (!newOnlinePlayers.isEmpty()) {
            log.info("New online players: {}", newOnlinePlayers);
            playerDetailsService.updateLastOnlineTimeByUserNames(new ArrayList<>(newOnlinePlayers));
        }

        // 更新离线时间和游戏时间
        if (!newOfflinePlayers.isEmpty()) {
            log.info("New offline players: {}", newOfflinePlayers);
            // 对每个下线的玩家计算游戏时间
            for (String player : newOfflinePlayers) {
                try {
                    PlayerDetails details = new PlayerDetails();
                    details.setUserName(player);
                    List<PlayerDetails> playerList = playerDetailsService.selectPlayerDetailsList(details);

                    if (!playerList.isEmpty()) {
                        PlayerDetails playerDetails = playerList.get(0);
                        Date lastOnlineTime = playerDetails.getLastOnlineTime();
                        Date now = new Date();

                        if (lastOnlineTime != null) {
                            // 计算本次游戏时间(分钟)
                            long gameTimeMinutes = (now.getTime() - lastOnlineTime.getTime()) / (1000 * 60);

                            // 更新总游戏时间，处理null值情况
                            Long currentGameTime = playerDetails.getGameTime();
                            currentGameTime = (currentGameTime == null) ? gameTimeMinutes : currentGameTime + gameTimeMinutes;
                            playerDetails.setGameTime(currentGameTime);

                            // 更新最后离线时间
                            playerDetails.setLastOfflineTime(now);

                            // 更新到数据库
                            playerDetailsService.updatePlayerDetails(playerDetails, false);

                            log.info("Updated game time for player {}: current session {} minutes, total {} minutes",
                                    player, gameTimeMinutes, currentGameTime);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    log.error("Failed to update game time for player {}: {}", player, e.getMessage());
                }
            }
        }

        // 更新缓存为当前在线玩家
        cache.setCacheObject(CacheKey.ONLINE_PLAYER_KEY, onlinePlayer);
    }

    /**
     * 命令重试
     */
    public void commandRetry() {
        log.debug("commandRetry start");
        Map<String, Object> map = new HashMap<>();
        if (cache.hasKey(CacheKey.ERROR_COMMAND_CACHE_KEY)) {
            map = cache.getCacheObject(CacheKey.ERROR_COMMAND_CACHE_KEY);
            if (map.isEmpty()) {
                return;
            }
            map.remove("@type");

            // 发送缓存中的命令
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Set<String> commands = (Set<String>) entry.getValue();
                if (commands.isEmpty()) {
                    continue;
                }
                boolean flag = key.contains("all");

                // 已执行命令
                Set<String> executedCommands = new HashSet<>();

                for (String command : commands) {
                    try {
                        if (flag) {
                            MapCache.getMap().forEach((k, v) -> {
                                try {
                                    v.sendCommand(command);
                                } catch (Exception e) {
                                    log.error("Failed to send command: {}", command, e);
                                }
                            });
                        } else {
                            if (MapCache.containsKey(key)) {
                                MapCache.get(key).sendCommand(command);
                            } else {
                                // 移除
                                executedCommands.add(command);
                            }
                        }
                        log.info("Successfully sent command: {}", command);

                        executedCommands.add(command);
                    } catch (Exception e) {
                        log.error("Failed to send command: {}", command, e);
                    }
                }

                // 移除已执行命令
                commands.removeAll(executedCommands);

                if (commands.isEmpty()) {
                    // 删除缓存
                    map.remove(key);
                }
                if (map.isEmpty()) {
                    // 删除缓存
                    cache.deleteObject(CacheKey.ERROR_COMMAND_CACHE_KEY);
                } else {
                    // 更新缓存
                    cache.setCacheObject(CacheKey.ERROR_COMMAND_CACHE_KEY, map);
                }

            }
        }
        log.debug("commandRetry end");
    }
}